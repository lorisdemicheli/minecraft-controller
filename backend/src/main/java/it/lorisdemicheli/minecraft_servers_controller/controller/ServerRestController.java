package it.lorisdemicheli.minecraft_servers_controller.controller;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import it.lorisdemicheli.minecraft_servers_controller.domain.Power;
import it.lorisdemicheli.minecraft_servers_controller.domain.Server;
import it.lorisdemicheli.minecraft_servers_controller.domain.Status;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/servers")
public class ServerRestController {


  @PostMapping(path = "", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public Server createServer(@RequestBody Server server) {

  }

  @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public Server findServer(@PathVariable Long id) {

  }

  @GetMapping(path = "", produces = MediaType.APPLICATION_JSON_VALUE)
  public List<Server> listServer() {

  }

  @DeleteMapping(path = "/{id}")
  public Server deleteServer(@PathVariable Long id) {

  }

  @GetMapping(value = "/{instanceId}/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<String>> logsV3(@PathVariable Long id) {
    return service.log(id);
  }
  
  @GetMapping(value = "/{instanceId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
  public Status findServerStatus(@PathVariable Long id) {
    return service.log(id);
  }
  
  @PostMapping(value = "/{instanceId}/power", produces = MediaType.APPLICATION_JSON_VALUE)
  public Status setServerPower(@PathVariable Long id, @RequestBody Power power) {
    return service.log(id);
  }
  
  @PostMapping(value = "/{instanceId}/command", produces = MediaType.APPLICATION_JSON_VALUE)
  public Status setServerPower(@PathVariable Long id, @RequestBody String command) {
    return service.log(id);
  }
}
