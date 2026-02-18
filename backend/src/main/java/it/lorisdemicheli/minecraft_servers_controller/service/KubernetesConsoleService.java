package it.lorisdemicheli.minecraft_servers_controller.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.kubernetes.client.Exec;
import io.kubernetes.client.openapi.ApiException;
import it.lorisdemicheli.minecraft_servers_controller.domain.PlayerDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerDescriptionDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceInfoDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerPopulationDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerState;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerVersionDto;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class KubernetesConsoleService {

	private static final String LOG_FILE = "/data/logs/latest.log";

	private final Exec exec;
	private final ObjectMapper objectMapper;
	private final Map<String, Flux<String>> activeStreams = new ConcurrentHashMap<>();

	public void sendMinecraftCommand(String namespace, String pod, String container, String command)
			throws ApiException, IOException {
		String cmd[] = { "gosu", "minecraft", "mc-send-to-console", command };
		exec.exec(namespace, pod, cmd, container, false, false);
		// proc.waitFor(); non serve aspettare
	}

	public Flux<String> streamLogs(String namespace, String pod, String container) {
		return activeStreams.computeIfAbsent(namespace + pod, k -> Flux.<String>create(sink -> {
			Process process = null;
			try {
				String[] cmd = { "tail", "-f", LOG_FILE };
				process = exec.exec(namespace, pod, cmd, container, false, false);
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
					String line;
					while (!sink.isCancelled() && (line = reader.readLine()) != null) {
						sink.next(line);
					}
				}

				sink.complete();
			} catch (Exception e) {
				sink.error(e);
			} finally {
				activeStreams.remove(namespace + pod);
				if (process != null) {
					process.destroy();
				}
			}
		}).subscribeOn(Schedulers.boundedElastic()) //
				.publish() //
				.refCount() //
				.cache(10) //
		);
	}

	public List<String> historyLogs(String namespace, String pod, String container, int limit, int skip) {
		List<String> lines = new ArrayList<>();

		String command = String.format("tail -n %d %s | head -n %d", skip + limit, LOG_FILE, limit);

		Process process = null;
		try {
			String[] cmd = { "sh", "-c", command };
			process = exec.exec(namespace, pod, cmd, container, false, false);

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					lines.add(line);
				}
			}

			process.waitFor(5, TimeUnit.SECONDS);
		} catch (Exception e) {
		} finally {
			if (process != null) {
				process.destroy();
			}
		}

		return lines;
	}

	public ServerInstanceInfoDto serverInfo(String namespace, String pod, String container, ServerState state)
			throws InterruptedException, ApiException, IOException {
		ServerInstanceInfoDto instanceDto = new ServerInstanceInfoDto(state);

		String[] cmd = { "mc-monitor", "status", "--host", "localhost", "--port", "25565", "--json" };
		Process process = exec.exec(namespace, pod, cmd, container, false, false);

		try (InputStream stdout = process.getInputStream()) {
			JsonNode root = objectMapper.readTree(stdout);

			boolean finished = process.waitFor(5, TimeUnit.SECONDS);
			if (!finished) {
				process.destroy();
				return instanceDto;
			}

			if (process.exitValue() == 0) {
				JsonNode serverInfo = root.path("server_info");
				if (!serverInfo.isMissingNode() && !serverInfo.isNull()) {
					if (serverInfo.has("version")) {
						instanceDto.setVersion(
								objectMapper.treeToValue(serverInfo.get("version"), ServerVersionDto.class));
					}
					if (serverInfo.has("players")) {
						ServerPopulationDto population = new ServerPopulationDto();
						JsonNode players = serverInfo.path("players");
						population.setOnline(players.path("online").asInt());
						population.setMax(players.path("max").asInt());
						population.setPlayers(objectMapper.convertValue(players.path("Samples"),
								new TypeReference<List<PlayerDto>>() {
								}));
						instanceDto.setPopulation(population);
					}
					if (serverInfo.has("description")) {
						instanceDto.setDescription(
								objectMapper.treeToValue(serverInfo.get("description"), ServerDescriptionDto.class));
					}
					if (serverInfo.has("favicon")) {
						instanceDto.setIcon(serverInfo.get("favicon").asString());
					}
				}
			}
			return instanceDto;
		} finally {
			if (process != null && process.isAlive()) {
				process.destroy();
			}
		}

	}
}
