package it.lorisdemicheli.minecraft_servers_controller.storage;

import java.util.Set;
import java.util.regex.Pattern;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;

/**
 * A server name is used as Kubernetes resource name, DNS label ({@code <name>.<baseDomain>}) and
 * directory name on the shared volume, so it must be a safe DNS-1035 label.
 */
public final class ServerNames {

  public static final int MAX_LENGTH = 40;

  /** Names that would collide with the platform's own hostnames. */
  private static final Set<String> RESERVED = Set.of("console", "api");

  private static final Pattern VALID =
      Pattern.compile("[a-z]([a-z0-9-]{0," + (MAX_LENGTH - 2) + "}[a-z0-9])?");

  private ServerNames() {}

  /** Validates a name of an existing/addressed server. */
  public static String requireValid(String name) {
    if (name == null || !VALID.matcher(name).matches()) {
      throw new InvalidRequestException("Invalid server name: use up to " + MAX_LENGTH
          + " lowercase letters, digits or '-', starting with a letter and not ending with '-'");
    }
    return name;
  }

  /** Validates a name for a server that is about to be created. */
  public static String requireNew(String name) {
    requireValid(name);
    if (RESERVED.contains(name)) {
      throw new ConflictException("Reserved name: " + name);
    }
    return name;
  }
}
