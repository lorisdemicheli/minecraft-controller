package it.lorisdemicheli.minecraft_servers_controller.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.lorisdemicheli.minecraft_servers_controller.domain.DiskUsageDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.FileEntry;
import it.lorisdemicheli.minecraft_servers_controller.exception.InvalidRequestException;
import it.lorisdemicheli.minecraft_servers_controller.storage.ServerStorage;
import it.lorisdemicheli.minecraft_servers_controller.storage.StoredFile;
import lombok.RequiredArgsConstructor;

/** File manager of a server. Works the same whether the server is running or stopped. */
@RestController
@Tag(name = "FILE SYSTEM")
@RequiredArgsConstructor
@RequestMapping("/servers/{serverName}/files")
public class ServerFileController {

  /** Bigger files can be downloaded but not opened in the editor. */
  private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;

  private final ServerStorage storage;

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<List<FileEntry>> listFiles(@PathVariable String serverName,
      @RequestParam(defaultValue = "/") String path) {
    return ResponseEntity
        .ok(storage.list(serverName, path).stream().map(FileEntry::from).toList());
  }

  @GetMapping(value = "/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable String serverName,
      @RequestParam String path) {
    StoredFile file = storage.stat(serverName, path);
    if (file.type() != StoredFile.Type.FILE) {
      throw new InvalidRequestException("Not a regular file: " + path);
    }
    StreamingResponseBody body = out -> {
      try (InputStream in = storage.openRead(serverName, path)) {
        in.transferTo(out);
      }
    };
    return ResponseEntity.ok() //
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build()
                .toString())
        .contentType(MediaType.APPLICATION_OCTET_STREAM) //
        .contentLength(file.size()) //
        .body(body);
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Void> uploadFile(@PathVariable String serverName,
      @RequestParam MultipartFile file, @RequestParam String destPath) throws IOException {
    try (InputStream in = file.getInputStream()) {
      storage.upload(serverName, destPath, file.getOriginalFilename(), in);
    }
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/directory")
  public ResponseEntity<Void> createDirectory(@PathVariable String serverName,
      @RequestParam String path) {
    storage.mkdir(serverName, path);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/touch")
  public ResponseEntity<Void> createEmptyFile(@PathVariable String serverName,
      @RequestParam String path) {
    storage.touch(serverName, path);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping
  public ResponseEntity<Void> deletePath(@PathVariable String serverName,
      @RequestParam String path) {
    storage.delete(serverName, path);
    return ResponseEntity.noContent().build();
  }

  @GetMapping(value = "/content", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> getContent(@PathVariable String serverName,
      @RequestParam String path) {
    return ResponseEntity.ok(storage.readText(serverName, path, MAX_TEXT_BYTES));
  }

  @PutMapping(value = "/content")
  public ResponseEntity<Void> setContent(@PathVariable String serverName,
      @RequestParam String path, @RequestBody String content) {
    storage.writeText(serverName, path, content);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/rename")
  public ResponseEntity<Void> rename(@PathVariable String serverName, @RequestParam String path,
      @RequestParam String newName) {
    storage.rename(serverName, path, newName);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/copy")
  public ResponseEntity<Void> copy(@PathVariable String serverName,
      @RequestParam String sourcePath, @RequestParam String destPath) {
    storage.copy(serverName, sourcePath, destPath);
    return ResponseEntity.noContent().build();
  }

  /** Disk used by this server and free/total space of the shared volume. */
  @GetMapping(value = "/usage", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<DiskUsageDto> usage(@PathVariable String serverName) {
    return ResponseEntity.ok(new DiskUsageDto(storage.usedBytes(serverName), storage.freeBytes(),
        storage.totalBytes()));
  }
}
