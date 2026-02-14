package it.lorisdemicheli.minecraft_servers_controller.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.lorisdemicheli.minecraft_servers_controller.annotation.Api;
import it.lorisdemicheli.minecraft_servers_controller.service.KubernetesServerInstanceService;
import reactor.core.publisher.Flux;

@Api
@RestController
@RequestMapping("/servers/{serverName}/console")
public class ServerConsoleController {

	@Autowired
	private KubernetesServerInstanceService service;

	@PostMapping(value = "/commands", consumes = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<Void> executeCommand( //
			@PathVariable String serverName, //
			@RequestBody String command) {
		service.sendCommand(serverName, command);
		return ResponseEntity.noContent().build();
	}

	@GetMapping(value = "/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> streamLogs(@PathVariable String serverName) {
		return service.logs(serverName);
	}
}