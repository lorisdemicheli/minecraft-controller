package it.lorisdemicheli.minecraft_servers_controller.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;

/**
 * All access to the shared data volume. Layout:
 *
 * <pre>
 * &lt;dataDir&gt;/&lt;server&gt;/...   data of one server (mounted in its pod with subPath=&lt;server&gt;)
 * &lt;dataDir&gt;/.trash/...     deleted servers, purged after a retention period
 * </pre>
 *
 * Every public method takes the server name plus a path <em>relative to that server</em>; paths
 * that escape the server directory (via {@code ..} or symlinks) are rejected.
 */
public class ServerStorage {

  public static final String TRASH_DIR = ".trash";

  private static final System.Logger LOG = System.getLogger(ServerStorage.class.getName());

  private static final Comparator<StoredFile> DIRECTORIES_FIRST = Comparator
      .comparing((StoredFile f) -> f.type() != StoredFile.Type.DIRECTORY)
      .thenComparing(f -> f.name().toLowerCase());

  private final Path root;
  private final Path trash;

  public ServerStorage(Path dataDir) {
    try {
      Files.createDirectories(dataDir);
      this.root = dataDir.toRealPath();
      this.trash = root.resolve(TRASH_DIR);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot initialise data directory " + dataDir, e);
    }
  }

  // ---------------------------------------------------------------- server directories

  public Path serverDir(String server) {
    return root.resolve(ServerNames.requireValid(server));
  }

  public boolean exists(String server) {
    return Files.isDirectory(serverDir(server), LinkOption.NOFOLLOW_LINKS);
  }

  public void createServerDir(String server) {
    try {
      Files.createDirectories(serverDir(server));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Moves the server's data to the trash (instant, even if a pod still has it mounted). */
  public boolean moveToTrash(String server) {
    Path dir = serverDir(server);
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    Path target = trash.resolve(server + "-" + System.currentTimeMillis());
    try {
      Files.createDirectories(trash);
      try {
        Files.move(dir, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(dir, target);
      }
      return true;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Permanently deletes trash entries older than {@code retention}. Returns how many. */
  public int purgeTrash(Duration retention) {
    if (!Files.isDirectory(trash, LinkOption.NOFOLLOW_LINKS)) {
      return 0;
    }
    long cutoff = System.currentTimeMillis() - retention.toMillis();
    int purged = 0;
    try (Stream<Path> entries = Files.list(trash)) {
      for (Path entry : entries.toList()) {
        String name = entry.getFileName().toString();
        int dash = name.lastIndexOf('-');
        try {
          long deletedAt = Long.parseLong(name.substring(dash + 1));
          if (dash > 0 && deletedAt <= cutoff) {
            deleteRecursively(entry);
            purged++;
          }
        } catch (NumberFormatException e) {
          // not created by us: leave it alone
        } catch (IOException e) {
          LOG.log(System.Logger.Level.WARNING, "Cannot purge trash entry " + name, e);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return purged;
  }

  // ---------------------------------------------------------------- disk usage

  public long usedBytes(String server) {
    Path dir = requireServerDir(server);
    long[] total = {0};
    try {
      Files.walkFileTree(dir, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (attrs.isRegularFile()) {
            total[0] += attrs.size();
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return total[0];
  }

  public long freeBytes() {
    try {
      return Files.getFileStore(root).getUsableSpace();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public long totalBytes() {
    try {
      return Files.getFileStore(root).getTotalSpace();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---------------------------------------------------------------- read

  public List<StoredFile> list(String server, String rel) {
    Path dir = resolve(server, rel, false);
    if (!Files.exists(dir)) {
      throw new ResourceNotFoundException("Directory not found: " + display(rel));
    }
    if (!Files.isDirectory(dir)) {
      throw new InvalidRequestException("Not a directory: " + display(rel));
    }
    try (Stream<Path> children = Files.list(dir)) {
      return children.map(ServerStorage::describe).flatMap(Optional::stream)
          .sorted(DIRECTORIES_FIRST).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Safe absolute path of a (possibly not yet existing) file of the server. */
  public Path path(String server, String rel) {
    return resolve(server, rel, false);
  }

  public StoredFile stat(String server, String rel) {
    Path file = resolve(server, rel, false);
    return describe(file)
        .orElseThrow(() -> new ResourceNotFoundException("File not found: " + display(rel)));
  }

  /** Caller must close the stream. */
  public InputStream openRead(String server, String rel) {
    Path file = requireRegularFile(resolve(server, rel, false), rel);
    try {
      return Files.newInputStream(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String readText(String server, String rel, long maxBytes) {
    Path file = requireRegularFile(resolve(server, rel, false), rel);
    try {
      if (Files.size(file) > maxBytes) {
        throw new InvalidRequestException(
            "File too large to edit (max " + maxBytes + " bytes): download it instead");
      }
      return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---------------------------------------------------------------- write

  public void writeText(String server, String rel, String content) {
    Path file = resolve(server, rel, false);
    requireParentDirectory(file, rel);
    if (Files.isDirectory(file)) {
      throw new InvalidRequestException("Is a directory: " + display(rel));
    }
    try {
      Files.writeString(file, content == null ? "" : content, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Stores {@code in}. If {@code destPath} is an existing directory the file is created inside it
   * with {@code filename}; otherwise {@code destPath} is the full target file path.
   */
  public void upload(String server, String destPath, String filename, InputStream in) {
    Path dest = resolve(server, destPath, false);
    Path target = Files.isDirectory(dest) ? child(server, dest, simpleName(filename)) : dest;
    if (Files.isDirectory(target)) {
      throw new InvalidRequestException("Target is a directory: " + display(destPath));
    }
    requireParentDirectory(target, destPath);
    try {
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void mkdir(String server, String rel) {
    Path dir = resolve(server, rel, false);
    try {
      Files.createDirectories(dir);
    } catch (FileAlreadyExistsException e) {
      throw new ConflictException("A file with that name already exists: " + display(rel));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void touch(String server, String rel) {
    Path file = resolve(server, rel, false);
    try {
      if (Files.exists(file)) {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
      } else {
        requireParentDirectory(file, rel);
        Files.createFile(file);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void delete(String server, String rel) {
    Path target = resolve(server, rel, true);
    if (target.equals(serverDir(server))) {
      throw new InvalidRequestException("Cannot delete the server root directory");
    }
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new ResourceNotFoundException("File not found: " + display(rel));
    }
    try {
      deleteRecursively(target);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void rename(String server, String rel, String newName) {
    Path source = resolve(server, rel, true);
    if (source.equals(serverDir(server))) {
      throw new InvalidRequestException("Cannot rename the server root directory");
    }
    if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
      throw new ResourceNotFoundException("File not found: " + display(rel));
    }
    if (newName == null || newName.contains("/") || newName.contains("\\")) {
      throw new InvalidRequestException("The new name must not contain path separators");
    }
    Path target = child(server, source.getParent(), simpleName(newName));
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new ConflictException("Already exists: " + target.getFileName());
    }
    try {
      Files.move(source, target);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Copies a file or directory. If {@code destPath} is an existing directory, copies inside it. */
  public void copy(String server, String sourcePath, String destPath) {
    Path source = resolve(server, sourcePath, false);
    if (!Files.exists(source)) {
      throw new ResourceNotFoundException("File not found: " + display(sourcePath));
    }
    Path target = resolve(server, destPath, false);
    if (Files.isDirectory(target)) {
      target = child(server, target, source.getFileName().toString());
    }
    if (Files.isDirectory(source) && target.startsWith(source)) {
      throw new InvalidRequestException("Cannot copy a directory into itself");
    }
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new ConflictException("Already exists: " + target.getFileName());
    }
    requireParentDirectory(target, destPath);
    try {
      if (Files.isDirectory(source)) {
        copyTree(source, target);
      } else {
        Files.copy(source, target);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---------------------------------------------------------------- internals

  private Path requireServerDir(String server) {
    Path dir = serverDir(server);
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      throw new ResourceNotFoundException("Server data directory not found: " + server);
    }
    return dir;
  }

  /**
   * Maps a user supplied relative path to an absolute one that is guaranteed to stay inside the
   * server directory, both lexically ({@code ..}) and physically (symlinks).
   *
   * @param leafMayBeLink true for delete/rename, which act on a symlink itself rather than on its
   *        target: then only the parent has to resolve inside the server directory.
   */
  private Path resolve(String server, String rel, boolean leafMayBeLink) {
    Path base = requireServerDir(server);
    String clean = rel == null ? "" : rel.replace('\\', '/');
    if (clean.indexOf('\0') >= 0) {
      throw new InvalidRequestException("Invalid path");
    }
    int start = 0;
    while (start < clean.length() && clean.charAt(start) == '/') {
      start++;
    }
    Path target;
    try {
      target = base.resolve(clean.substring(start)).normalize();
    } catch (InvalidPathException e) {
      throw new InvalidRequestException("Invalid path");
    }
    if (!target.startsWith(base)) {
      throw new InvalidRequestException("Path escapes the server directory");
    }

    Path probe = (leafMayBeLink && !target.equals(base)) ? target.getParent() : target;
    while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
      probe = probe.getParent();
    }
    try {
      if (probe == null || !probe.toRealPath().startsWith(base)) {
        throw new InvalidRequestException("Path escapes the server directory");
      }
    } catch (IOException e) {
      throw new InvalidRequestException("Cannot resolve path");
    }
    return target;
  }

  /** {@code dir/name}, re-validated through {@link #resolve}. */
  private Path child(String server, Path dir, String name) {
    Path base = serverDir(server);
    return resolve(server, base.relativize(dir.resolve(name)).toString(), false);
  }

  private static String simpleName(String filename) {
    String name = filename == null ? "" : filename;
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    if (name.isBlank() || name.equals(".") || name.equals("..") || name.indexOf('\0') >= 0) {
      throw new InvalidRequestException("Invalid file name");
    }
    return name;
  }

  private static Path requireRegularFile(Path file, String rel) {
    if (!Files.exists(file)) {
      throw new ResourceNotFoundException("File not found: " + display(rel));
    }
    if (!Files.isRegularFile(file)) {
      throw new InvalidRequestException("Not a regular file: " + display(rel));
    }
    return file;
  }

  private static void requireParentDirectory(Path file, String rel) {
    Path parent = file.getParent();
    if (parent == null || !Files.isDirectory(parent)) {
      throw new ResourceNotFoundException("Parent directory not found: " + display(rel));
    }
  }

  private static String display(String rel) {
    return rel == null || rel.isEmpty() ? "/" : rel;
  }

  private static Optional<StoredFile> describe(Path path) {
    try {
      BasicFileAttributes a =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      StoredFile.Type type = a.isDirectory() ? StoredFile.Type.DIRECTORY
          : a.isRegularFile() ? StoredFile.Type.FILE : StoredFile.Type.OTHER;
      return Optional.of(new StoredFile(path.getFileName().toString(), type, a.size(),
          a.lastModifiedTime().toInstant()));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Symlinks are removed, never followed. */
  private static void deleteRecursively(Path target) throws IOException {
    Files.walkFileTree(target, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        if (exc != null) {
          throw exc;
        }
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /** Symlinks inside the source are skipped. */
  private static void copyTree(Path source, Path target) throws IOException {
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        Files.createDirectories(target.resolve(source.relativize(dir).toString()));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (attrs.isRegularFile()) {
          Files.copy(file, target.resolve(source.relativize(file).toString()));
        }
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
