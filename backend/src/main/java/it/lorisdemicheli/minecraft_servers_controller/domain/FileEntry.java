package it.lorisdemicheli.minecraft_servers_controller.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public record FileEntry(String name, FileType type, long sizeBytes, LocalDateTime lastModified) {
  public enum FileType {
    FILE, DIRECTORY, OTHER
  }

  public static FileEntry fromStat(String fullPath, String typeStr, long size, long unixTime) {
    String fileName = fullPath.substring(fullPath.lastIndexOf('/') + 1);
    FileType fileType =
        typeStr.toLowerCase().contains("directory") ? FileType.DIRECTORY : FileType.FILE;
    LocalDateTime ldt =
        LocalDateTime.ofInstant(Instant.ofEpochSecond(unixTime), ZoneId.systemDefault());

    return new FileEntry(fileName, fileType, size, ldt);
  }
}
