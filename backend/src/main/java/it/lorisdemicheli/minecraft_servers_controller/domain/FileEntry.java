package it.lorisdemicheli.minecraft_servers_controller.domain;

import java.time.LocalDateTime;
import java.time.ZoneId;
import it.lorisdemicheli.minecraft_servers_controller.storage.StoredFile;

public record FileEntry(String name, FileType type, long sizeBytes, LocalDateTime lastModified) {
  public enum FileType {
    FILE, DIRECTORY, OTHER
  }

  public static FileEntry from(StoredFile file) {
    return new FileEntry(file.name(), FileType.valueOf(file.type().name()), file.size(),
        LocalDateTime.ofInstant(file.lastModified(), ZoneId.systemDefault()));
  }
}
