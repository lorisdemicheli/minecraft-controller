package it.lorisdemicheli.minecraft_servers_controller.controller;

import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import it.lorisdemicheli.minecraft_servers_controller.domain.ConfigurableOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.FileEntry;
import it.lorisdemicheli.minecraft_servers_controller.domain.Server;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInfo;
import it.lorisdemicheli.minecraft_servers_controller.domain.Type;
import it.lorisdemicheli.minecraft_servers_controller.service.KubernetesServerInstanceService;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/server")
public class ServerRestController {

  @Autowired
  private KubernetesServerInstanceService service;

  @PostMapping(path = "/create/{type}/{serverName}", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Server> createServer(@PathVariable Type type,
      @PathVariable String serverName, @RequestBody ConfigurableOptions options) {
    Server serverCreated = service.createServer(serverName, type, options);

    URI location = MvcUriComponentsBuilder
        .fromMethodName(ServerRestController.class, "getServer", serverCreated.getName()) //
        .build() //
        .toUri();

    return ResponseEntity.created(location).body(serverCreated);
  }

  @GetMapping(path = "/{serverName}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Server> getServer(@PathVariable String serverName) {
    Server server = service.getServer(serverName);
    return ResponseEntity.ok(server);
  }

  @GetMapping(path = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<List<Server>> listServer() {
    List<Server> serverList = service.getServerList();
    return ResponseEntity.ok(serverList);
  }

  @DeleteMapping(path = "/{serverName}")
  public ResponseEntity<Void> deleteServer(@PathVariable String serverName) {
    service.deleteServer(serverName);
    return ResponseEntity.noContent().build();
  }


  @GetMapping(value = "/{serverName}/info", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ServerInfo> getServerInfo(@PathVariable String serverName) {
    ServerInfo info = service.getServerInfo(serverName);
    return ResponseEntity.ok(info);
  }

  @PostMapping(value = "/{serverName}/start")
  public ResponseEntity<Void> startServer(@PathVariable String serverName) {
    service.startServer(serverName);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/{serverName}/stop")
  public ResponseEntity<Void> stopServer(@PathVariable String serverName) {
    service.stopServer(serverName);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/{serverName}/terminate")
  public ResponseEntity<Void> terminateServer(@PathVariable String serverName) {
    service.terminateServer(serverName);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/{serverName}/execute-command", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> executeCommand(@PathVariable String serverName,
      @RequestBody String command) {
    service.sendCommand(serverName, command);
    return ResponseEntity.noContent().build();
  }

  @GetMapping(value = "/{serverName}/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<String> logs(@PathVariable String serverName) {
    return service.logs(serverName);
  }

  @GetMapping(value = "/{serverName}/files", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<List<FileEntry>> listFiles(@PathVariable String serverName,
      @RequestParam String path) {
    var files = service.listFiles(serverName, path);
    return ResponseEntity.ok(files);
  }

  @GetMapping(value = "/{serverName}/file/download",
      produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable String serverName,
      @RequestParam String path) {
    String filename = path.substring(path.lastIndexOf("/") + 1);
    return ResponseEntity.ok() //
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"") //
        .contentType(MediaType.APPLICATION_OCTET_STREAM) //
        .body((out) -> {
          service.downloadFile(serverName, path, out);
        });
  }

  // @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  // public ResponseEntity<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file,
  // @RequestParam("destPath") String destPath) {
  // try {
  // // String fullPath = destPath.endsWith("/") ? destPath + file.getOriginalFilename() :
  // // destPath;
  //
  // k8sService.uploadFile(NAMESPACE, POD_NAME, CONTAINER_NAME, destPath, file);
  //
  // return ResponseEntity.ok(Map.of("message", "Upload completato con successo: " + destPath));
  // } catch (Exception e) {
  // return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
  // }
  // }
  //
  // // 4. CREA CARTELLA
  // @PostMapping("/mkdir")
  // public ResponseEntity<String> createDirectory(@RequestParam String path) {
  // try {
  // k8sService.createDirectory(NAMESPACE, POD_NAME, CONTAINER_NAME, path);
  // return ResponseEntity.ok("Cartella creata");
  // } catch (Exception e) {
  // return ResponseEntity.status(500).body("Errore: " + e.getMessage());
  // }
  // }
  //
  // @DeleteMapping("/delete")
  // public ResponseEntity<String> delete(@RequestParam String path) {
  // try {
  // k8sService.deletePath(NAMESPACE, POD_NAME, CONTAINER_NAME, path);
  // return ResponseEntity.ok("Elemento eliminato");
  // } catch (Exception e) {
  // return ResponseEntity.status(500).body("Errore: " + e.getMessage());
  // }
  // }
  //
  // @PostMapping("/touch")
  // public ResponseEntity<String> touch(@RequestParam String path) {
  // try {
  // k8sService.createEmptyFile(NAMESPACE, POD_NAME, CONTAINER_NAME, path);
  // return ResponseEntity.ok("File creato");
  // } catch (Exception e) {
  // return ResponseEntity.status(500).body("Errore: " + e.getMessage());
  // }
  // }
}
