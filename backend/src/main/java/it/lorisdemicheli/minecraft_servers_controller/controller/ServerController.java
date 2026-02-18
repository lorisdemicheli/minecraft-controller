package it.lorisdemicheli.minecraft_servers_controller.controller;

import java.net.URI;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
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

import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceInfoDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.SmartServerTypeDto;
import it.lorisdemicheli.minecraft_servers_controller.service.KubernetesServerInstanceService;

//@Api
@RestController
@RequestMapping("/servers")
public class ServerController {

	@Autowired
	private KubernetesServerInstanceService service;

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<List<ServerInstanceDto>> getAllServers() {
		return ResponseEntity.ok(service.getServerList());
	}

	@GetMapping(path = "/{serverName}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ServerInstanceDto> getServer(@PathVariable String serverName) {
		return ResponseEntity.ok(service.getServer(serverName));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ServerInstanceDto> createServer(@RequestParam String name, @RequestParam SmartServerTypeDto type,
			@RequestBody ServerInstanceDto instance) {

		ServerInstanceDto serverCreated = service.createServer(instance);

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
		service.deleteServer(serverName);
		return ResponseEntity.noContent().build();
	}

	@GetMapping(path = "/{serverName}/info", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ServerInstanceInfoDto> getServerInfo(@PathVariable String serverName) {
		return ResponseEntity.ok(service.getServerInfo(serverName));
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
}