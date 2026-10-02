package it.lorisdemicheli.minecraft_servers_controller.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;

class ServerNamesTest {

  @Test
  void acceptsDnsLabels() {
    assertEquals("survival", ServerNames.requireValid("survival"));
    assertEquals("a", ServerNames.requireValid("a"));
    assertEquals("mc-1-test", ServerNames.requireValid("mc-1-test"));
    assertEquals("a".repeat(40), ServerNames.requireValid("a".repeat(40)));
  }

  @Test
  void rejectsUnsafeNames() {
    for (String bad : new String[] {null, "", "..", ".trash", "../x", "a/b", "a\\b", "Abc", "1abc",
        "-abc", "abc-", "a_b", "a b", "a.b", "a".repeat(41)}) {
      assertThrows(InvalidRequestException.class, () -> ServerNames.requireValid(bad), "name: " + bad);
    }
  }

  @Test
  void reservedNamesCannotBeCreated() {
    assertThrows(ConflictException.class, () -> ServerNames.requireNew("console"));
    assertThrows(ConflictException.class, () -> ServerNames.requireNew("api"));
    assertEquals("apiary", ServerNames.requireNew("apiary"));
  }
}
