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

import it.lorisdemicheli.minecraft_servers_controller.domain.ConfigurableOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.Server;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInfo;
import it.lorisdemicheli.minecraft_servers_controller.domain.Type;
import it.lorisdemicheli.minecraft_servers_controller.service.KubernetesServerInstanceService;

//@Api
@RestController
@RequestMapping("/servers")
public class ServerController {

	@Autowired
	private KubernetesServerInstanceService service;

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<List<Server>> getAllServers() {
		return ResponseEntity.ok(service.getServerList());
	}

	@GetMapping(path = "/{serverName}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Server> getServer(@PathVariable String serverName) {
		return ResponseEntity.ok(service.getServer(serverName));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Server> createServer(@RequestParam String name, @RequestParam Type type,
			@RequestBody ConfigurableOptions options) {

		Server serverCreated = service.createServer(name, type, options);

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
	public ResponseEntity<ServerInfo> getServerInfo(@PathVariable String serverName) {
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