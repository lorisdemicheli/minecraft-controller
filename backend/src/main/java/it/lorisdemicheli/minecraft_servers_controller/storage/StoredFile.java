package it.lorisdemicheli.minecraft_servers_controller.storage;

import java.time.Instant;

public record StoredFile(String name, Type type, long size, Instant lastModified) {
  public enum Type {
    FILE, DIRECTORY, OTHER
  }
}
