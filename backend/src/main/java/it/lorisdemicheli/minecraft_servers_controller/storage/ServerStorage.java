package it.lorisdemicheli.minecraft_servers_controller.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;

/**
 * All access to the shared data volume. Layout:
 *
 * <pre>
 * &lt;dataDir&gt;/&lt;server&gt;/...            data of one server (mounted in its pod with subPath=&lt;server&gt;)
 * &lt;dataDir&gt;/.trash/...              deleted servers, purged after a retention period
 * &lt;dataDir&gt;/.pending-deletion/&lt;server&gt; deletions waiting for the pod to stop
 * </pre>
 *
 * Every public method takes the server name plus a path <em>relative to that server</em>.
 *
 * <p><b>Confinement.</b> The content of a server directory is written by the Minecraft process and
 * its plugins, so it is untrusted: any entry can be (or become, at any moment) a symbolic link.
 * Paths are therefore never resolved by the operating system from the volume root. Each operation
 * opens the server directory and then every component of the path with {@link SecureDirectoryStream}
 * ({@code openat}/{@code unlinkat}/{@code renameat} relative to an open directory) and
 * {@link LinkOption#NOFOLLOW_LINKS}: a symlink anywhere in the path makes the operation fail, even
 * if it appeared after a previous check (no TOCTOU). Symlinks are listed, renamed and deleted as
 * links, never followed. The only operation that still goes through a path is the creation of
 * directories (Java has no {@code mkdirat}); the created directory is then reopened safely.
 *
 * <p><b>Writes</b> go to a temporary file in the same directory that is renamed over the target,
 * so the Minecraft process never reads a half written file.
 */
public class ServerStorage {

  public static final String TRASH_DIR = ".trash";
  public static final String PENDING_DELETION_DIR = ".pending-deletion";

  /** Temporary files of writes in progress: hidden from listings. */
  static final String TEMP_PREFIX = ".mc-controller-tmp-";

  private static final System.Logger LOG = System.getLogger(ServerStorage.class.getName());
  private static final int MAX_NAME_BYTES = 255;

  private static final Comparator<StoredFile> DIRECTORIES_FIRST = Comparator
      .comparing((StoredFile f) -> f.type() != StoredFile.Type.DIRECTORY)
      .thenComparing(f -> f.name().toLowerCase());

  private final Path root;
  private final Path trash;
  private final Path pending;
  private final long minFreeBytes;

  public ServerStorage(Path dataDir) {
    this(dataDir, 0);
  }

  /** @param minFreeBytes uploads are refused if they would leave less free space than this */
  public ServerStorage(Path dataDir, long minFreeBytes) {
    try {
      Files.createDirectories(dataDir);
      this.root = dataDir.toRealPath();
      this.trash = root.resolve(TRASH_DIR);
      this.pending = root.resolve(PENDING_DELETION_DIR);
      this.minFreeBytes = minFreeBytes;
      try (DirectoryStream<Path> probe = Files.newDirectoryStream(root)) {
        if (!(probe instanceof SecureDirectoryStream)) {
          throw new IllegalStateException("The data directory " + root + " does not support "
              + "SecureDirectoryStream (needed to confine file access): use Linux or macOS");
        }
      }
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

  /** @return true if the directory was created, false if it already existed (adopted data) */
  public boolean createServerDir(String server) {
    Path dir = serverDir(server);
    try {
      Files.createDirectory(dir);
      return true;
    } catch (FileAlreadyExistsException e) {
      if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
        throw new ConflictException("The data volume holds a non-directory named " + server);
      }
      return false;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Rollback helper: removes the server directory only if it is empty, so it can never destroy
   * data. @return true if removed
   */
  public boolean deleteServerDirIfEmpty(String server) {
    String name = ServerNames.requireValid(server);
    try (SecureDirectoryStream<Path> rootDir = openRoot()) {
      rootDir.deleteDirectory(Path.of(name));
      return true;
    } catch (NoSuchFileException | DirectoryNotEmptyException | NotDirectoryException e) {
      return false;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Moves the server's data to the trash (an atomic rename). Only call it once no pod has the
   * directory mounted any more, see {@link #markPendingDeletion}.
   */
  public boolean moveToTrash(String server) {
    String name = ServerNames.requireValid(server);
    if (!exists(name)) {
      return false;
    }
    try {
      Files.createDirectories(trash);
      try (SecureDirectoryStream<Path> rootDir = openRoot();
          SecureDirectoryStream<Path> trashDir =
              rootDir.newDirectoryStream(Path.of(TRASH_DIR), LinkOption.NOFOLLOW_LINKS)) {
        rootDir.move(Path.of(name), trashDir, Path.of(name + "-" + System.currentTimeMillis()));
      }
      return true;
    } catch (NoSuchFileException e) {
      return false;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Permanently deletes trash entries older than {@code retention}; a zero retention disables the
   * purge. Entries that were not created by {@link #moveToTrash} are left alone.
   */
  public PurgeResult purgeTrash(Duration retention) {
    if (retention.isZero() || retention.isNegative()
        || !Files.isDirectory(trash, LinkOption.NOFOLLOW_LINKS)) {
      return new PurgeResult(0, 0);
    }
    long cutoff = System.currentTimeMillis() - retention.toMillis();
    int purged = 0;
    int failed = 0;
    try (SecureDirectoryStream<Path> rootDir = openRoot();
        SecureDirectoryStream<Path> trashDir =
            rootDir.newDirectoryStream(Path.of(TRASH_DIR), LinkOption.NOFOLLOW_LINKS)) {
      for (Path entry : names(trashDir)) {
        String name = entry.toString();
        int dash = name.lastIndexOf('-');
        long deletedAt;
        try {
          deletedAt = dash > 0 ? Long.parseLong(name.substring(dash + 1)) : Long.MAX_VALUE;
        } catch (NumberFormatException e) {
          continue; // not created by us
        }
        if (deletedAt > cutoff) {
          continue;
        }
        try {
          deleteRecursively(trashDir, entry);
          purged++;
        } catch (IOException e) {
          failed++;
          LOG.log(System.Logger.Level.WARNING, "Cannot purge trash entry " + name, e);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new PurgeResult(purged, failed);
  }

  public record PurgeResult(int purged, int failed) {
  }

  // ---------------------------------------------------------------- pending deletions

  /**
   * Remembers (on the volume, so it survives a restart) that the data of {@code server} must go
   * to the trash once its pod is gone. While marked, a server with the same name cannot be created.
   */
  public void markPendingDeletion(String server) {
    markPendingDeletion(server, Instant.now());
  }

  public void markPendingDeletion(String server, Instant since) {
    String name = ServerNames.requireValid(server);
    try {
      Files.createDirectories(pending);
      Files.writeString(pending.resolve(name), since.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public boolean isPendingDeletion(String server) {
    return Files.exists(pending.resolve(ServerNames.requireValid(server)),
        LinkOption.NOFOLLOW_LINKS);
  }

  /** When the deletion was requested, empty if it is not pending. */
  public java.util.Optional<Instant> pendingDeletionSince(String server) {
    Path marker = pending.resolve(ServerNames.requireValid(server));
    try {
      try {
        return java.util.Optional.of(Instant.parse(Files.readString(marker).strip()));
      } catch (java.time.format.DateTimeParseException e) {
        return java.util.Optional.of(
            Files.getLastModifiedTime(marker, LinkOption.NOFOLLOW_LINKS).toInstant());
      }
    } catch (NoSuchFileException e) {
      return java.util.Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void clearPendingDeletion(String server) {
    try {
      Files.deleteIfExists(pending.resolve(ServerNames.requireValid(server)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public List<String> pendingDeletions() {
    if (!Files.isDirectory(pending, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(pending)) {
      return entries.map(p -> p.getFileName().toString()).filter(ServerStorage::isServerName)
          .sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean isServerName(String name) {
    try {
      ServerNames.requireValid(name);
      return true;
    } catch (InvalidRequestException e) {
      return false;
    }
  }

  // ---------------------------------------------------------------- disk usage

  /**
   * Bytes of the regular files of a server. Entries that cannot be read are counted in
   * {@link DiskUsage#unreadableEntries()}: then the total is only a lower bound.
   */
  public DiskUsage usage(String server) {
    long[] total = {0};
    int[] errors = {0};
    try (SecureDirectoryStream<Path> dir = openServer(server)) {
      sizeOf(dir, total, errors);
    } catch (IOException e) {
      throw io(e, "/");
    }
    if (errors[0] > 0) {
      LOG.log(System.Logger.Level.WARNING,
          "Disk usage of " + server + " is partial: " + errors[0] + " unreadable entries");
    }
    return new DiskUsage(total[0], errors[0]);
  }

  /** @param unreadableEntries > 0 means {@code bytes} is a lower bound */
  public record DiskUsage(long bytes, int unreadableEntries) {
    public boolean complete() {
      return unreadableEntries == 0;
    }
  }

  public long usedBytes(String server) {
    return usage(server).bytes();
  }

  private static void sizeOf(SecureDirectoryStream<Path> dir, long[] total, int[] errors) {
    List<Path> entries;
    try {
      entries = names(dir);
    } catch (IOException | RuntimeException e) {
      errors[0]++;
      return;
    }
    for (Path name : entries) {
      try {
        BasicFileAttributes a = attributes(dir, name);
        if (a.isRegularFile()) {
          total[0] += a.size();
        } else if (a.isDirectory()) {
          try (SecureDirectoryStream<Path> child =
              dir.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
            sizeOf(child, total, errors);
          }
        }
      } catch (NoSuchFileException e) {
        // removed while walking: not an error
      } catch (IOException e) {
        errors[0]++;
      }
    }
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
    RelPath path = RelPath.parse(rel);
    try (SecureDirectoryStream<Path> dir = openDir(server, path.parts(), rel)) {
      List<StoredFile> files = new ArrayList<>();
      for (Path name : names(dir)) {
        if (name.toString().startsWith(TEMP_PREFIX)) {
          continue;
        }
        try {
          files.add(describe(name.toString(), attributes(dir, name)));
        } catch (IOException e) {
          // vanished or unreadable while listing: skip it
        }
      }
      files.sort(DIRECTORIES_FIRST);
      return files;
    } catch (IOException e) {
      throw io(e, rel);
    }
  }

  /**
   * Lexically safe absolute path of a (possibly not yet existing) file of the server. It does not
   * protect from symlinks: read files through this class ({@link #openRead}, {@link #logSource}).
   */
  public Path path(String server, String rel) {
    Path dir = serverDir(server);
    List<String> parts = RelPath.parse(rel).parts();
    return parts.isEmpty() ? dir : dir.resolve(String.join("/", parts));
  }

  public StoredFile stat(String server, String rel) {
    RelPath path = RelPath.parse(rel);
    if (path.isRoot()) {
      requireServerDir(server);
      try (SecureDirectoryStream<Path> rootDir = openRoot()) {
        return describe(server, attributes(rootDir, Path.of(server)));
      } catch (IOException e) {
        throw io(e, rel);
      }
    }
    try (SecureDirectoryStream<Path> dir = openDir(server, path.parent(), rel)) {
      return describe(path.leaf(), attributes(dir, Path.of(path.leaf())));
    } catch (IOException e) {
      throw io(e, rel);
    }
  }

  /** Caller must close the stream. The file is opened without following symlinks. */
  public InputStream openRead(String server, String rel) {
    return Channels.newInputStream(openRegularFile(server, rel));
  }

  public String readText(String server, String rel, long maxBytes) {
    try (InputStream in = openRead(server, rel)) {
      byte[] content = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
      if (content.length > maxBytes) {
        throw new InvalidRequestException(
            "File too large to edit (max " + maxBytes + " bytes): download it instead");
      }
      return new String(content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw io(e, rel);
    }
  }

  /**
   * Opens a server file for {@link LogFile}: the source answers {@code null} while the file does
   * not exist (or is not a regular file, e.g. a symlink planted in its place).
   */
  public LogFile.Source logSource(String server, String rel) {
    requireServerDir(server);
    RelPath path = RelPath.parse(rel);
    return () -> {
      try {
        return openRegularFile(server, path, rel);
      } catch (ResourceNotFoundException | InvalidRequestException e) {
        return null;
      }
    };
  }

  private SeekableByteChannel openRegularFile(String server, String rel) {
    return openRegularFile(server, RelPath.parse(rel), rel);
  }

  private SeekableByteChannel openRegularFile(String server, RelPath path, String rel) {
    if (path.isRoot()) {
      throw new InvalidRequestException("Not a regular file: " + display(rel));
    }
    try (SecureDirectoryStream<Path> dir = openDir(server, path.parent(), rel)) {
      Path leaf = Path.of(path.leaf());
      BasicFileAttributes a = attributes(dir, leaf);
      if (a.isSymbolicLink()) {
        throw new InvalidRequestException("Path escapes the server directory: " + display(rel));
      }
      if (!a.isRegularFile()) {
        throw new InvalidRequestException("Not a regular file: " + display(rel));
      }
      return dir.newByteChannel(leaf, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    } catch (IOException e) {
      throw io(e, rel);
    }
  }

  // ---------------------------------------------------------------- write

  public void writeText(String server, String rel, String content) {
    RelPath path = RelPath.parse(rel);
    if (path.isRoot()) {
      throw new InvalidRequestException("Is a directory: " + display(rel));
    }
    byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
    try (SecureDirectoryStream<Path> dir = openParent(server, path, rel)) {
      replaceAtomically(dir, path.leaf(), new java.io.ByteArrayInputStream(bytes), rel);
    } catch (IOException e) {
      throw io(e, rel);
    }
  }

  public void upload(String server, String destPath, String filename, InputStream in) {
    upload(server, destPath, filename, in, -1);
  }

  /**
   * Stores {@code in} (streamed, never held in memory). If {@code destPath} is an existing
   * directory the file is created inside it with {@code filename}; otherwise {@code destPath} is
   * the full target file path.
   *
   * @param expectedSize size announced by the client, -1 if unknown; used to refuse uploads that
   *        would leave less than the configured minimum free space
   */
  public void upload(String server, String destPath, String filename, InputStream in,
      long expectedSize) {
    RelPath dest = RelPath.parse(destPath);
    if (expectedSize > 0 && freeBytes() - expectedSize < minFreeBytes) {
      throw new ConflictException("Not enough free space on the data volume");
    }
    try {
      RelPath target = dest.isRoot() || isDirectory(server, dest, destPath)
          ? dest.child(simpleName(filename))
          : dest;
      try (SecureDirectoryStream<Path> dir = openParent(server, target, destPath)) {
        replaceAtomically(dir, target.leaf(), in, destPath);
      }
    } catch (IOException e) {
      throw io(e, destPath);
    }
  }

  public void mkdir(String server, String rel) {
    RelPath path = RelPath.parse(rel);
    List<String> done = new ArrayList<>();
    try {
      SecureDirectoryStream<Path> dir = openServer(server);
      try {
        for (String part : path.parts()) {
          Path name = Path.of(part);
          BasicFileAttributes a = attributesOrNull(dir, name);
          if (a == null) {
            createDirectory(server, done, part);
          } else if (a.isSymbolicLink()) {
            throw new InvalidRequestException("Path escapes the server directory: " + display(rel));
          } else if (!a.isDirectory()) {
            throw new ConflictException("A file with that name already exists: " + display(rel));
          }
          SecureDirectoryStream<Path> next = dir.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
          dir.close();
          dir = next;
          done.add(part);
        }
      } finally {
        dir.close();
      }
    } catch (IOException e) {
      throw io(e, rel);
    }
  }

  public void touch(String server, String rel) {
    RelPath path = RelPath.parse(rel);
    if (path.isRoot()) {
      return;
    }
    try (SecureDirectoryStream<Path> dir = openParent(server, path, rel)) {
      Path leaf = Path.of(path.leaf());
      if (attributesOrNull(dir, leaf) != null) {
        FileTime now = FileTime.from(Instant.now());
        dir.getFileAttributeView(leaf, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
            .setTimes(now, null, null);
      } else {
        dir.newByteChannel(leaf, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)).close();
      }
    } catch (IOException e) {
      throw io(e, rel);
    }
  }

  /** Deletes a file or a whole directory. Symlinks are removed, never followed. */
  public void delete(String server, String rel) {
    RelPath path = RelPath.parse(rel);
    if (path.isRoot()) {
      throw new InvalidRequestException("Cannot delete the server root directory");
    }
    try (SecureDirectoryStream<Path> dir = openParent(server, path, rel)) {
      Path leaf = Path.of(path.leaf());
      attributes(dir, leaf); // NoSuchFile -> 404
      deleteRecursively(dir, leaf);
    } catch (IOException e) {
      throw io(e, rel);
    }
  }

  public void rename(String server, String rel, String newName) {
    RelPath path = RelPath.parse(rel);
    if (path.isRoot()) {
      throw new InvalidRequestException("Cannot rename the server root directory");
    }
    if (newName == null || newName.contains("/") || newName.contains("\\")) {
      throw new InvalidRequestException("The new name must not contain path separators");
    }
    String target = simpleName(newName);
    try (SecureDirectoryStream<Path> dir = openParent(server, path, rel)) {
      Path source = Path.of(path.leaf());
      attributes(dir, source); // NoSuchFile -> 404
      if (attributesOrNull(dir, Path.of(target)) != null) {
        throw new ConflictException("Already exists: " + target);
      }
      dir.move(source, dir, Path.of(target));
    } catch (IOException e) {
      throw io(e, rel);
    }
  }

  /** Copies a file or directory. If {@code destPath} is an existing directory, copies inside it. */
  public void copy(String server, String sourcePath, String destPath) {
    RelPath source = RelPath.parse(sourcePath);
    RelPath dest = RelPath.parse(destPath);
    if (source.isRoot()) {
      throw new InvalidRequestException("Cannot copy the server root directory");
    }
    try (SecureDirectoryStream<Path> sourceDir = openDir(server, source.parent(), sourcePath)) {
      Path sourceName = Path.of(source.leaf());
      BasicFileAttributes a = attributes(sourceDir, sourceName);
      if (a.isSymbolicLink() || !(a.isDirectory() || a.isRegularFile())) {
        throw new InvalidRequestException("Only files and directories can be copied");
      }
      RelPath target = dest.isRoot() || isDirectory(server, dest, destPath)
          ? dest.child(source.leaf())
          : dest;
      if (a.isDirectory() && target.startsWith(source)) {
        throw new InvalidRequestException("Cannot copy a directory into itself");
      }
      try (SecureDirectoryStream<Path> targetDir = openParent(server, target, destPath)) {
        Path targetName = Path.of(target.leaf());
        if (attributesOrNull(targetDir, targetName) != null) {
          throw new ConflictException("Already exists: " + target.leaf());
        }
        try {
          if (a.isDirectory()) {
            try (SecureDirectoryStream<Path> from =
                sourceDir.newDirectoryStream(sourceName, LinkOption.NOFOLLOW_LINKS)) {
              copyTree(server, from, targetDir, target);
            }
          } else {
            copyFile(sourceDir, sourceName, targetDir, targetName);
          }
        } catch (IOException | RuntimeException e) {
          try {
            deleteRecursively(targetDir, targetName); // do not leave half a copy behind
          } catch (IOException cleanup) {
            e.addSuppressed(cleanup);
          }
          throw e;
        }
      }
    } catch (IOException e) {
      throw io(e, sourcePath);
    }
  }

  // ---------------------------------------------------------------- internals: navigation

  private SecureDirectoryStream<Path> openRoot() throws IOException {
    return (SecureDirectoryStream<Path>) Files.newDirectoryStream(root);
  }

  private void requireServerDir(String server) {
    if (!exists(server)) {
      throw new ResourceNotFoundException("Server data directory not found: " + server);
    }
  }

  /** The server directory, opened without following a symlink. */
  private SecureDirectoryStream<Path> openServer(String server) throws IOException {
    String name = ServerNames.requireValid(server);
    requireServerDir(name);
    try (SecureDirectoryStream<Path> rootDir = openRoot()) {
      return rootDir.newDirectoryStream(Path.of(name), LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      throw new ResourceNotFoundException("Server data directory not found: " + server);
    }
  }

  /** Opens {@code parts} below the server directory, one component at a time, never following links. */
  private SecureDirectoryStream<Path> openDir(String server, List<String> parts, String rel)
      throws IOException {
    SecureDirectoryStream<Path> dir = openServer(server);
    try {
      for (int i = 0; i < parts.size(); i++) {
        Path name = Path.of(parts.get(i));
        BasicFileAttributes a = attributesOrNull(dir, name);
        if (a == null) {
          throw new ResourceNotFoundException(
              (i == parts.size() - 1 ? "Directory not found: " : "Parent directory not found: ")
                  + display(rel));
        }
        if (a.isSymbolicLink()) {
          throw new InvalidRequestException("Path escapes the server directory: " + display(rel));
        }
        if (!a.isDirectory()) {
          throw new InvalidRequestException("Not a directory: " + display(rel));
        }
        SecureDirectoryStream<Path> next = dir.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
        dir.close();
        dir = next;
      }
      return dir;
    } catch (IOException | RuntimeException e) {
      dir.close();
      throw e;
    }
  }

  private SecureDirectoryStream<Path> openParent(String server, RelPath path, String rel)
      throws IOException {
    try {
      return openDir(server, path.parent(), rel);
    } catch (ResourceNotFoundException e) {
      throw new ResourceNotFoundException("Parent directory not found: " + display(rel), e);
    }
  }

  private boolean isDirectory(String server, RelPath path, String rel) throws IOException {
    try (SecureDirectoryStream<Path> dir = openParent(server, path, rel)) {
      BasicFileAttributes a = attributesOrNull(dir, Path.of(path.leaf()));
      return a != null && a.isDirectory();
    }
  }

  /**
   * Java has no {@code mkdirat}: the directory is created through its path (the server directory
   * plus components that have just been opened without following links), then reopened with
   * NOFOLLOW by the caller, which fails if anything was swapped in the meantime.
   */
  private void createDirectory(String server, List<String> parents, String name)
      throws IOException {
    Path dir = serverDir(server);
    for (String p : parents) {
      dir = dir.resolve(p);
    }
    try {
      Files.createDirectory(dir.resolve(name));
    } catch (FileAlreadyExistsException e) {
      // created concurrently: the caller checks what it is when opening it
    }
  }

  // ---------------------------------------------------------------- internals: operations

  /**
   * Streams {@code in} to a temporary file next to {@code leaf}, then renames it over
   * {@code leaf} (atomic). Permissions of the replaced file are kept.
   */
  private static void replaceAtomically(SecureDirectoryStream<Path> dir, String leaf,
      InputStream in, String rel) throws IOException {
    Path target = Path.of(leaf);
    BasicFileAttributes existing = attributesOrNull(dir, target);
    if (existing != null && existing.isDirectory()) {
      throw new InvalidRequestException("Is a directory: " + display(rel));
    }
    if (existing != null && existing.isSymbolicLink()) {
      throw new InvalidRequestException("Path escapes the server directory: " + display(rel));
    }
    Path temp = Path.of(TEMP_PREFIX + UUID.randomUUID());
    Set<OpenOption> create = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS);
    try {
      try (SeekableByteChannel channel = dir.newByteChannel(temp, create);
          OutputStream out = Channels.newOutputStream(channel)) {
        in.transferTo(out);
      }
      if (existing != null) {
        copyPermissions(dir, target, temp);
      }
      dir.move(temp, dir, target);
    } catch (IOException | RuntimeException e) {
      try {
        dir.deleteFile(temp);
      } catch (IOException cleanup) {
        // already moved or never created
      }
      throw e;
    }
  }

  private static void copyPermissions(SecureDirectoryStream<Path> dir, Path from, Path to) {
    try {
      PosixFileAttributeView source =
          dir.getFileAttributeView(from, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      PosixFileAttributeView target =
          dir.getFileAttributeView(to, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      if (source != null && target != null) {
        Set<PosixFilePermission> permissions = source.readAttributes().permissions();
        target.setPermissions(permissions);
      }
    } catch (IOException | UnsupportedOperationException e) {
      // best effort: the content matters more than the mode
    }
  }

  private static void copyFile(SecureDirectoryStream<Path> fromDir, Path from,
      SecureDirectoryStream<Path> toDir, Path to) throws IOException {
    try (SeekableByteChannel in = fromDir.newByteChannel(from,
        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        SeekableByteChannel out = toDir.newByteChannel(to, Set.of(StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
      Channels.newInputStream(in).transferTo(Channels.newOutputStream(out));
    }
  }

  /** Symlinks and special files inside the source are skipped. */
  private void copyTree(String server, SecureDirectoryStream<Path> from,
      SecureDirectoryStream<Path> targetParent, RelPath target) throws IOException {
    createDirectory(server, target.parent(), target.leaf());
    try (SecureDirectoryStream<Path> to =
        targetParent.newDirectoryStream(Path.of(target.leaf()), LinkOption.NOFOLLOW_LINKS)) {
      for (Path name : names(from)) {
        if (name.toString().startsWith(TEMP_PREFIX)) {
          continue;
        }
        BasicFileAttributes a = attributesOrNull(from, name);
        if (a == null || a.isSymbolicLink()) {
          continue;
        }
        if (a.isRegularFile()) {
          copyFile(from, name, to, name);
        } else if (a.isDirectory()) {
          try (SecureDirectoryStream<Path> child =
              from.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
            copyTree(server, child, to, target.child(name.toString()));
          }
        }
      }
    }
  }

  /**
   * Deletes {@code name} inside {@code dir}; directories are emptied first. Files created by the
   * Minecraft process while deleting get a second chance before giving up.
   */
  private static void deleteRecursively(SecureDirectoryStream<Path> dir, Path name)
      throws IOException {
    BasicFileAttributes a = attributesOrNull(dir, name);
    if (a == null) {
      return;
    }
    if (!a.isDirectory()) {
      dir.deleteFile(name);
      return;
    }
    for (int attempt = 1;; attempt++) {
      try (SecureDirectoryStream<Path> child =
          dir.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
        for (Path entry : names(child)) {
          deleteRecursively(child, entry);
        }
      }
      try {
        dir.deleteDirectory(name);
        return;
      } catch (DirectoryNotEmptyException e) {
        if (attempt >= 3) {
          throw e;
        }
      }
    }
  }

  // ---------------------------------------------------------------- internals: helpers

  /** Entry names of a directory (relative paths, safe to pass back to {@code dir}). */
  private static List<Path> names(SecureDirectoryStream<Path> dir) throws IOException {
    List<Path> names = new ArrayList<>();
    try {
      for (Path entry : dir) {
        names.add(entry.getFileName());
      }
    } catch (java.nio.file.DirectoryIteratorException e) {
      throw e.getCause();
    }
    return names;
  }

  private static BasicFileAttributes attributes(SecureDirectoryStream<Path> dir, Path name)
      throws IOException {
    return dir.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
        .readAttributes();
  }

  private static BasicFileAttributes attributesOrNull(SecureDirectoryStream<Path> dir, Path name)
      throws IOException {
    try {
      return attributes(dir, name);
    } catch (NoSuchFileException e) {
      return null;
    }
  }

  private static StoredFile describe(String name, BasicFileAttributes a) {
    StoredFile.Type type = a.isDirectory() ? StoredFile.Type.DIRECTORY
        : a.isRegularFile() ? StoredFile.Type.FILE : StoredFile.Type.OTHER;
    return new StoredFile(name, type, a.size(), a.lastModifiedTime().toInstant());
  }

  static String simpleName(String filename) {
    String name = filename == null ? "" : filename;
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    validateName(name);
    return name;
  }

  private static void validateName(String name) {
    if (name.isBlank() || name.equals(".") || name.equals("..")
        || name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES
        || name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      throw new InvalidRequestException("Invalid file name");
    }
  }

  private static String display(String rel) {
    return rel == null || rel.isEmpty() ? "/" : rel;
  }

  /** Maps I/O failures to the application's exceptions (messages are safe for the client). */
  private static RuntimeException io(IOException e, String rel) {
    if (e instanceof NoSuchFileException) {
      return new ResourceNotFoundException("File not found: " + display(rel), e);
    }
    if (e instanceof FileAlreadyExistsException) {
      return new ConflictException("Already exists: " + display(rel), e);
    }
    if (e instanceof DirectoryNotEmptyException) {
      return new ConflictException("The directory changed while deleting it, try again", e);
    }
    if (e instanceof NotDirectoryException) {
      return new InvalidRequestException("Not a directory: " + display(rel), e);
    }
    if (e instanceof AccessDeniedException) {
      return new ConflictException("Permission denied: " + display(rel), e);
    }
    if (e instanceof FileSystemException fse && fse.getReason() != null
        && (fse.getReason().contains("symbolic link") || fse.getReason().contains("ELOOP"))) {
      // a link was swapped in after the checks: NOFOLLOW refused it
      return new InvalidRequestException("Path escapes the server directory: " + display(rel), e);
    }
    return new UncheckedIOException(e);
  }

  /**
   * A user supplied path relative to the server directory, normalized lexically: empty and
   * {@code .} components are dropped, {@code ..} removes the previous component and may not climb
   * above the server directory. Backslashes count as separators.
   */
  record RelPath(List<String> parts) {

    static RelPath parse(String rel) {
      String clean = rel == null ? "" : rel.replace('\\', '/');
      if (clean.indexOf('\0') >= 0) {
        throw new InvalidRequestException("Invalid path");
      }
      Deque<String> parts = new ArrayDeque<>();
      for (String part : clean.split("/")) {
        if (part.isEmpty() || part.equals(".")) {
          continue;
        }
        if (part.equals("..")) {
          if (parts.isEmpty()) {
            throw new InvalidRequestException("Path escapes the server directory");
          }
          parts.removeLast();
          continue;
        }
        if (part.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
          throw new InvalidRequestException("Invalid path: name too long");
        }
        parts.addLast(part);
      }
      return new RelPath(List.copyOf(parts));
    }

    boolean isRoot() {
      return parts.isEmpty();
    }

    List<String> parent() {
      return parts.isEmpty() ? List.of() : parts.subList(0, parts.size() - 1);
    }

    String leaf() {
      return parts.get(parts.size() - 1);
    }

    RelPath child(String name) {
      List<String> child = new ArrayList<>(parts);
      child.add(name);
      return new RelPath(List.copyOf(child));
    }

    boolean startsWith(RelPath other) {
      return parts.size() >= other.parts.size()
          && parts.subList(0, other.parts.size()).equals(other.parts);
    }
  }
}
