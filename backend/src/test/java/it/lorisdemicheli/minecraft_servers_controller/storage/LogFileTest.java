package it.lorisdemicheli.minecraft_servers_controller.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFileTest {

  @TempDir
  Path tmp;

  private Path file(String content) throws IOException {
    Path f = tmp.resolve("latest.log");
    Files.writeString(f, content);
    return f;
  }

  private static String lines(int from, int to) {
    return IntStream.rangeClosed(from, to).mapToObj(i -> "line " + i + "\n")
        .collect(Collectors.joining());
  }

  @Test
  void tailReturnsLastCompleteLines() throws IOException {
    Path f = file("a\nb\nc\n");
    assertEquals(List.of("b", "c"), LogFile.tail(f, 2).lines());
    assertEquals(List.of("a", "b", "c"), LogFile.tail(f, 10).lines());
    assertEquals(6, LogFile.tail(f, 2).position());
  }

  @Test
  void tailIgnoresHalfWrittenLastLine() throws IOException {
    Path f = file("a\nb\npartial");
    LogFile.Chunk c = LogFile.tail(f, 5);
    assertEquals(List.of("a", "b"), c.lines());
    assertEquals(4, c.position());
  }

  @Test
  void tailOnBigFileAcrossBlocks() throws IOException {
    Path f = file(lines(1, 5000));
    List<String> last = LogFile.tail(f, 50).lines();
    assertEquals(50, last.size());
    assertEquals("line 4951", last.get(0));
    assertEquals("line 5000", last.get(49));
  }

  @Test
  void tailHandlesMissingEmptyAndCrLf() throws IOException {
    assertEquals(List.of(), LogFile.tail(tmp.resolve("nope.log"), 5).lines());
    assertEquals(List.of(), LogFile.tail(file(""), 5).lines());
    assertEquals(List.of("x", "y"), LogFile.tail(file("x\r\ny\r\n"), 5).lines());
  }

  @Test
  void tailHandlesUtf8() throws IOException {
    Path f = file("caffè ☕\nné\n");
    assertEquals(List.of("caffè ☕", "né"), LogFile.tail(f, 5).lines());
  }

  @Test
  void windowMatchesHeadTailSemantics() throws IOException {
    Path f = file(lines(1, 10));
    assertEquals(List.of("line 8", "line 9", "line 10"), LogFile.window(f, 3, 0));
    assertEquals(List.of("line 5", "line 6", "line 7"), LogFile.window(f, 3, 3));
    assertEquals(List.of("line 1", "line 2"), LogFile.window(f, 5, 8));   // fewer than limit left
    assertEquals(List.of(), LogFile.window(f, 5, 10));                     // skipped everything
    assertEquals(List.of(), LogFile.window(f, 0, 0));
  }

  @Test
  void followDeliversOnlyNewCompleteLines() throws IOException {
    Path f = file("a\nb\n");
    long pos = LogFile.tail(f, 0).position();
    assertEquals(4, pos);
    assertEquals(List.of(), LogFile.readFrom(f, pos).lines());

    Files.writeString(f, "c\nd", StandardOpenOption.APPEND);
    LogFile.Chunk c1 = LogFile.readFrom(f, pos);
    assertEquals(List.of("c"), c1.lines());            // "d" is not complete yet

    Files.writeString(f, "e\nf\n", StandardOpenOption.APPEND);
    LogFile.Chunk c2 = LogFile.readFrom(f, c1.position());
    assertEquals(List.of("de", "f"), c2.lines());      // the half line is completed
    assertEquals(List.of(), LogFile.readFrom(f, c2.position()).lines());
  }

  @Test
  void followSurvivesRotation() throws IOException {
    Path f = file(lines(1, 100));
    long pos = LogFile.tail(f, 0).position();

    Files.write(f, "fresh\n".getBytes(StandardCharsets.UTF_8)); // new, smaller latest.log
    LogFile.Chunk c = LogFile.readFrom(f, pos);
    assertEquals(List.of("fresh"), c.lines());
    assertTrue(c.position() < pos);

    Files.delete(f);                                            // momentarily missing
    assertEquals(0, LogFile.readFrom(f, c.position()).position());
  }
}
