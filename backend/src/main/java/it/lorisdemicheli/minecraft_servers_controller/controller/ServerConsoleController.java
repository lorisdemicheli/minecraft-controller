package it.lorisdemicheli.minecraft_servers_controller.controller;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceInfoDto;
import it.lorisdemicheli.minecraft_servers_controller.service.MinecraftServerInstance;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

// @Api
@RestController
@Tag(name = "CONSOLE")
@RequiredArgsConstructor
@RequestMapping("/servers/{serverName}/console")
public class ServerConsoleController {

  private final MinecraftServerInstance service;

  @GetMapping(path = "/info", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ServerInstanceInfoDto> getServerInfo(@PathVariable String serverName) {
    return ResponseEntity.ok(service.getServerInfo(serverName));
  }
  
  @GetMapping(value = "/info/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<ServerInstanceInfoDto>> getStreamServerInfo(@PathVariable String serverName) {
    return service.getStreamServerInfo(serverName);
  }

  @PostMapping(value = "/start")
  public ResponseEntity<Void> startServer(@PathVariable String serverName) {
    service.startServer(serverName);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/stop")
  public ResponseEntity<Void> stopServer(@PathVariable String serverName) {
    service.stopServer(serverName);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/terminate")
  public ResponseEntity<Void> terminateServer(@PathVariable String serverName) {
    service.terminateServer(serverName);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/commands", consumes = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<Void> executeCommand( //
      @PathVariable String serverName, //
      @RequestBody String command) {
    service.sendMinecraftCommand(serverName, command);
    return ResponseEntity.noContent().build();
  }

  @GetMapping(value = "/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<String> streamLogs(@PathVariable String serverName) {
    return service.getLogs(serverName);
  }

  @GetMapping(value = "/logs/history", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<List<String>> historyLogs(@PathVariable String serverName, //
      @RequestParam(defaultValue = "100") int limit, //
      @RequestParam(defaultValue = "0") int skip) {
    List<String> historyLogs = service.getHistoryLogs(serverName, limit, skip);
    return ResponseEntity.ok(historyLogs);
  }
}
