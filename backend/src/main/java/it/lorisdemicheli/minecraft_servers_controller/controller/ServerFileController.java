package it.lorisdemicheli.minecraft_servers_controller.controller;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import it.lorisdemicheli.minecraft_servers_controller.domain.FileEntry;
import it.lorisdemicheli.minecraft_servers_controller.service.MinecraftServerInstance;
import lombok.RequiredArgsConstructor;

// @Api
@RestController
@RequiredArgsConstructor
@RequestMapping("/servers/{serverName}/files")
public class ServerFileController {

  private final MinecraftServerInstance service;

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<List<FileEntry>> listFiles(@PathVariable String serverName,
      @RequestParam(defaultValue = "/") String path) {
    return ResponseEntity.ok(service.getFiles(serverName, path));
  }

  @GetMapping(value = "/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable String serverName,
      @RequestParam String path) {

    String filename = path.contains("/") ? path.substring(path.lastIndexOf("/") + 1) : path;

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(out -> service.downloadFile(serverName, path, out));
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Void> uploadFile(@PathVariable String serverName,
      @RequestParam MultipartFile file, @RequestParam String destPath) {
    service.uploadFile(serverName, destPath, file.getResource());
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/directory")
  public ResponseEntity<Void> createDirectory(@PathVariable String serverName,
      @RequestParam String path) {
    service.createDirectory(serverName, path);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/touch")
  public ResponseEntity<Void> createEmptyFile(@PathVariable String serverName,
      @RequestParam String path) {
    service.createEmptyFile(serverName, path);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping
  public ResponseEntity<Void> deletePath(@PathVariable String serverName,
      @RequestParam String path) {
    service.deletePath(serverName, path);
    return ResponseEntity.noContent().build();
  }
}
