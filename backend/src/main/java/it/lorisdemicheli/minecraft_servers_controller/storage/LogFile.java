package it.lorisdemicheli.minecraft_servers_controller.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a growing text file (Minecraft's {@code logs/latest.log}) without any shell or Kubernetes
 * involvement. Only <em>complete</em> lines are ever returned; a half-written last line is picked
 * up by the next read.
 */
public final class LogFile {

  /** Lines read plus the byte offset just after the last complete line. */
  public record Chunk(List<String> lines, long position) {
  }

  private static final int BLOCK = 8192;
  private static final int MAX_READ = 1 << 20;
  private static final long MAX_LINES = 100_000;

  private LogFile() {}

  /** Last {@code n} complete lines of the file. */
  public static Chunk tail(Path file, int n) {
    if (!Files.isRegularFile(file)) {
      return new Chunk(List.of(), 0);
    }
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      long end = endOfLastLine(raf, raf.length());
      if (end == 0) {
        return new Chunk(List.of(), 0);
      }
      if (n <= 0) {
        return new Chunk(List.of(), end);
      }
      long start = startOfLastLines(raf, end, n);
      byte[] buf = new byte[(int) (end - start)];
      raf.seek(start);
      raf.readFully(buf);
      return new Chunk(decode(buf, buf.length), end);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Lines {@code [total-skip-limit, total-skip)}: i.e. skip the last {@code skip} lines, then take
   * the {@code limit} lines before them.
   */
  public static List<String> window(Path file, int limit, int skip) {
    if (limit <= 0) {
      return List.of();
    }
    int safeSkip = Math.max(0, skip);
    int wanted = (int) Math.min((long) safeSkip + limit, MAX_LINES);
    List<String> all = tail(file, wanted).lines();
    int end = Math.max(0, all.size() - safeSkip);
    int start = Math.max(0, end - limit);
    return List.copyOf(all.subList(start, end));
  }

  /**
   * New complete lines after {@code position}. If the file shrank (rotation at midnight) or
   * disappeared, reading restarts from the beginning.
   */
  public static Chunk readFrom(Path file, long position) {
    if (!Files.isRegularFile(file)) {
      return new Chunk(List.of(), 0);
    }
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      long size = raf.length();
      long from = size < position ? 0 : position;
      if (size == from) {
        return new Chunk(List.of(), from);
      }
      int len = (int) Math.min(size - from, MAX_READ);
      byte[] buf = new byte[len];
      raf.seek(from);
      raf.readFully(buf);

      int lastNewline = -1;
      for (int i = len - 1; i >= 0; i--) {
        if (buf[i] == '\n') {
          lastNewline = i;
          break;
        }
      }
      if (lastNewline < 0) {
        // No complete line yet. Only emit if a single line is absurdly long, to avoid stalling.
        return len == MAX_READ ? new Chunk(decode(buf, len), from + len) : new Chunk(List.of(), from);
      }
      return new Chunk(decode(buf, lastNewline + 1), from + lastNewline + 1);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Offset just after the last '\n', or 0 if there is none. */
  private static long endOfLastLine(RandomAccessFile raf, long size) throws IOException {
    byte[] buf = new byte[BLOCK];
    long pos = size;
    while (pos > 0) {
      int len = (int) Math.min(BLOCK, pos);
      pos -= len;
      raf.seek(pos);
      raf.readFully(buf, 0, len);
      for (int i = len - 1; i >= 0; i--) {
        if (buf[i] == '\n') {
          return pos + i + 1;
        }
      }
    }
    return 0;
  }

  /** Offset where the last {@code n} lines (ending at {@code end}) begin. */
  private static long startOfLastLines(RandomAccessFile raf, long end, int n) throws IOException {
    byte[] buf = new byte[BLOCK];
    long pos = end;
    int seen = 0;
    while (pos > 0) {
      int len = (int) Math.min(BLOCK, pos);
      pos -= len;
      raf.seek(pos);
      raf.readFully(buf, 0, len);
      for (int i = len - 1; i >= 0; i--) {
        // the final '\n' (at end-1) is #1; the one before the first wanted line is #n+1
        if (buf[i] == '\n' && ++seen == n + 1) {
          return pos + i + 1;
        }
      }
    }
    return 0;
  }

  /** Decodes {@code length} bytes (ending right after a '\n') into lines, stripping '\r'. */
  private static List<String> decode(byte[] buf, int length) {
    String text = new String(buf, 0, length, StandardCharsets.UTF_8);
    List<String> lines = new ArrayList<>();
    int from = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        int to = (i > from && text.charAt(i - 1) == '\r') ? i - 1 : i;
        lines.add(text.substring(from, to));
        from = i + 1;
      }
    }
    if (from < text.length()) {
      lines.add(text.substring(from)); // only for the oversized-line escape hatch
    }
    return List.copyOf(lines);
  }
}
