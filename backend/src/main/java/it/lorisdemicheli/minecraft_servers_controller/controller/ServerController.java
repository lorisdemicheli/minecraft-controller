package it.lorisdemicheli.minecraft_servers_controller.controller;

import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceDto;
import it.lorisdemicheli.minecraft_servers_controller.service.MinecraftServerInstance;
import lombok.RequiredArgsConstructor;

// @Api
@RestController
@RequiredArgsConstructor
@RequestMapping("/servers")
public class ServerController {

  private final MinecraftServerInstance service;

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<List<ServerInstanceDto>> getAllServers() {
    return ResponseEntity.ok(service.list());
  }

  @GetMapping(path = "/{serverName}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ServerInstanceDto> getServer(@PathVariable String serverName) {
    return ResponseEntity.ok(service.read(serverName));
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ServerInstanceDto> createServer(@RequestBody ServerInstanceDto instance) {

    ServerInstanceDto serverCreated = service.create(instance);

    URI location = MvcUriComponentsBuilder //
        .fromMethodName( //
            ServerController.class, //
            "getServer", //
            serverCreated.getName())//
        .build().toUri();

    return ResponseEntity.created(location).body(serverCreated);
  }

  @DeleteMapping(path = "/{serverName}")
  public ResponseEntity<Void> deleteServer(@PathVariable String serverName) {
    service.delete(serverName);
    return ResponseEntity.noContent().build();
  }
}
