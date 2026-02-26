package it.lorisdemicheli.minecraft_servers_controller.service;

import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.Strings;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1ExecAction;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpec;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1Probe;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1StatefulSetSpec;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import io.kubernetes.client.openapi.models.V1VolumeResourceRequirements;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerLabel;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.FileEntry;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceInfoDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.SmartServerTypeDto;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class MinecraftServerInstance {

	private final KubernetesAsyncService kubernetesService;
	private final MinecraftConsoleService minecraftConsoleService;
	private final KubernetesExplorerService explorerService;
	private final KubernetesFileSystemService fileSystemService;

	private final MinecraftServerOptions serverOptions;

	private final static String CONTAINER_NAME = "minecraft";
	private final static String MANAGED_BY = "minecraft-controller";;

	// ----- CRUD -----

	public ServerInstanceDto create(ServerInstanceDto server) {
		validate(server);

		List<V1EnvVar> envs = new ArrayList<>();
		envs.addAll(server.getType().getEnvs(server, serverOptions));
		envs.add(new V1EnvVar().name("EULA").value(Boolean.toString(server.isEula())));
		envs.add(new V1EnvVar().name("CREATE_CONSOLE_IN_PIPE").value("TRUE"));
		envs.add(new V1EnvVar().name("MEMORY").value(String.format("%dM", server.getMemory())));
		envs.add(new V1EnvVar().name("JVM_OPTS").value("--enable-native-access=ALL-UNNAMED"));

		V1Probe healthProbe = new V1Probe().exec(new V1ExecAction().addCommandItem("mc-health")) //
				.initialDelaySeconds(60) //
				.periodSeconds(20);

		V1Probe startupProbe = new V1Probe().exec(new V1ExecAction().addCommandItem("mc-health")) //
				.initialDelaySeconds(30) //
				.periodSeconds(10) //
				.failureThreshold(60);

		Map<String, String> annotations = new HashMap<>();
		String externalDomain = String.format("%s.%s", server.getName(), serverOptions.getBaseDomain());
		annotations.put("mc-router.itzg.me/externalServerName", externalDomain);

		V1Service service = new V1Service() //
				.metadata(new V1ObjectMeta() //
						.name(server.getName()) //
						.labels(getSelectorLabels(server)) //
						.annotations(annotations)) //
				.spec(new V1ServiceSpec() //
						.selector(getSelectorLabels(server)) //
						.addPortsItem(new V1ServicePort() //
								.protocol("TCP") //
								.port(25565) //
								.targetPort(new IntOrString(25565))));

		V1StatefulSet statefulSet = new V1StatefulSet() //
				.metadata(new V1ObjectMeta() //
						.name(server.getName()) //
						.labels(getLabels(server)))
				.spec(new V1StatefulSetSpec() //
						.serviceName(server.getName()) //
						.replicas(0) //
						.selector(new V1LabelSelector() //
								.matchLabels(getSelectorLabels(server)))
						.template(new V1PodTemplateSpec() //
								.metadata(new V1ObjectMeta() //
										.labels(getSelectorLabels(server)))
								.spec(new V1PodSpec() //
										.addContainersItem(new V1Container() //
												.name(CONTAINER_NAME) //
												.image("itzg/minecraft-server") //
												.env(envs) //
												.resources(getRequirements(server)) //
												.livenessProbe(healthProbe) //
												.readinessProbe(healthProbe) //
												.startupProbe(startupProbe) //
												.addVolumeMountsItem(new V1VolumeMount() //
														.name("data") //
														.mountPath("/data")))
										.addVolumesItem(new V1Volume() //
												.name("data")
												.persistentVolumeClaim(new V1PersistentVolumeClaimVolumeSource()
														.claimName(server.getName()))))));

		V1PersistentVolumeClaim pvc = new V1PersistentVolumeClaim() //
				.metadata(new V1ObjectMeta() //
						.name(server.getName()) //
						.labels(getSelectorLabels(server))) //
				.spec(new V1PersistentVolumeClaimSpec() //
						.accessModes(Arrays.asList("ReadWriteOnce")) //
						.resources(new V1VolumeResourceRequirements() //
								.putRequestsItem("storage", new Quantity("10Gi"))));

		return kubernetesService.createNamespacedPersistentVolumeClaim( //
				serverOptions.getNamespace(), //
				pvc //
		// ).then(explorerService.createExplorerPod( //
		// serverOptions.getNamespace(), //
		// server.getName() //
		// ) //
		).then( //
				kubernetesService.createNamespacedService( //
						serverOptions.getNamespace(), //
						service //
				) //
		).then( //
				kubernetesService.createNamespacedStatefulSet( //
						serverOptions.getNamespace(), //
						statefulSet //
				) //
		).map(this::toDto) //
				.subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(30));
	}

	public ServerInstanceDto read(String serverName) {
		return kubernetesService.getNamespacedStatefulSet( //
				serverOptions.getNamespace(), //
				serverName //
		).map(this::toDto) //
				.subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(2));
	}

	public ServerInstanceDto update(ServerInstanceDto server) {
		validate(server);

		return kubernetesService.getNamespacedStatefulSet( //
				serverOptions.getNamespace(), //
				server.getName() //
		).flatMap(statefulSet -> {
			statefulSet.getMetadata().setLabels(getLabels(server));
			statefulSet.getSpec().getSelector().setMatchLabels(getSelectorLabels(server));
			statefulSet.getSpec().getTemplate().getMetadata().setLabels(getSelectorLabels(server));

			List<V1EnvVar> envs = server.getType().getEnvs(server, serverOptions);

			envs.add(new V1EnvVar().name("EULA").value(Boolean.toString(server.isEula())));
			envs.add(new V1EnvVar().name("CREATE_CONSOLE_IN_PIPE").value("TRUE"));
			envs.add(new V1EnvVar().name("MEMORY").value(String.format("%dM", server.getMemory())));

			V1Container container = statefulSet.getSpec().getTemplate().getSpec().getContainers().get(0);
			container.setEnv(envs);

			container.setResources(getRequirements(server));

			return kubernetesService.replaceNamespacedStatefulSet( //
					server.getName(), //
					serverOptions.getNamespace(), //
					statefulSet //
			);
		}).map(this::toDto) //
				.subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(30));
	}

	public void delete(String serverName) {
		kubernetesService.deleteNamespacedStatefulSet( //
				serverOptions.getNamespace(), //
				serverName //
		).then( //
				kubernetesService.deleteNamespacedService( //
						serverOptions.getNamespace(), //
						serverName //
				) //
		).then( //
				kubernetesService.deleteNamespacedPersistentVolumeClaim( //
						serverOptions.getNamespace(), //
						serverName //
				) //
		).then( //
				explorerService.deleteExplorerPod( //
						serverOptions.getNamespace(), //
						serverName //
				).onErrorComplete() //
		).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(30));
	}

	public List<ServerInstanceDto> list() {
		return kubernetesService.getNamespacedStatefulSets( //
				serverOptions.getNamespace(), //
				String.format("%s=%s", MinecraftServerLabel.LABEL_MANAGED_BY, MANAGED_BY) //
		).map(this::toDtos) //
				.subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(5));
	}

	// ----- CONSOLE -----

	// public void startServer(String serverName) {
	// explorerService.deleteExplorerPod( //
	// serverOptions.getNamespace(), //
	// serverName //
	// ).flatMap(v -> {
	// return kubernetesService.getNamespacedStatefulSet( //
	// serverOptions.getNamespace(), //
	// serverName //
	// );
	// }).flatMap(statefulSet -> {
	// statefulSet.getSpec().setReplicas(1);
	// return kubernetesService.replaceNamespacedStatefulSet( //
	// serverOptions.getNamespace(), //
	// serverName, //
	// statefulSet //
	// );
	// }).subscribeOn(Schedulers.boundedElastic()) //
	// .block(Duration.ofSeconds(10));
	// }

	public void startServer(String serverName) {
		kubernetesService.getNamespacedStatefulSet( //
				serverOptions.getNamespace(), //
				serverName //
		).flatMap(statefulSet -> {
			statefulSet.getSpec().setReplicas(1);
			return kubernetesService.replaceNamespacedStatefulSet( //
					serverOptions.getNamespace(), //
					serverName, //
					statefulSet //
			);
		}).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(10));
	}

	public void stopServer(String serverName) {
		kubernetesService.getNamespacedStatefulSet( //
				serverOptions.getNamespace(), //
				serverName //
		).flatMap(statefulSet -> {
			statefulSet.getSpec().setReplicas(0);
			return kubernetesService.replaceNamespacedStatefulSet( //
					serverOptions.getNamespace(), //
					serverName, //
					statefulSet //
			);
//    }).flatMap(v -> {
//      return explorerService.createExplorerPod( //
//          serverOptions.getNamespace(), //
//          serverName //
//      );
		}).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(10));
	}

	public void terminateServer(String serverName) {
		kubernetesService.getNamespacedStatefulSet( //
				serverOptions.getNamespace(), //
				serverName //
		).flatMap(statefulSet -> {
			statefulSet.getSpec().setReplicas(0);
			return kubernetesService.replaceNamespacedStatefulSet( //
					serverOptions.getNamespace(), //
					serverName, //
					statefulSet //
			);
		}).flatMap(v -> {
			return kubernetesService.deleteNamespacedPodInstantly( //
					serverOptions.getNamespace(), //
					getPodName(serverName) //
			);
//    }).flatMap(v -> {
//      return explorerService.createExplorerPod( //
//          serverOptions.getNamespace(), //
//          serverName //
//      );
		}).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(10));
	}

	public void sendMinecraftCommand(String serverName, String command) {
		minecraftConsoleService.sendMinecraftCommand( //
				serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME, //
				command//
		).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(20));
	}

	public List<String> getHistoryLogs(String serverName, int limit, int skip) {
		return minecraftConsoleService.getLogs(//
				serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME, //
				limit, //
				skip //
		).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(5));
	}

	public Flux<String> getLogs(String serverName) {
		return minecraftConsoleService.getStreamLogs( //
				serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME //
		).subscribeOn(Schedulers.boundedElastic());
	}

	public ServerInstanceInfoDto getServerInfo(String serverName) {
		return minecraftConsoleService.getServerInfo( //
				serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME //
		).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(2));
	}

	public Flux<ServerSentEvent<ServerInstanceInfoDto>> getStreamServerInfo(String serverName) {
		return minecraftConsoleService.getStreamServerInfo( //
				serverName, //
				serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME //
		).subscribeOn(Schedulers.boundedElastic());
	}

	// ----- FILE SYSTEM -----

	public List<FileEntry> getFiles(String serverName, String path) {
		final String finalPath = normalizePath(path);

		return fileSystemService.getFiles( //
				serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME, //
				finalPath //
		).onErrorResume(e -> {
			return fileSystemService.getFiles( //
					serverOptions.getNamespace(), //
					explorerService.getExplorerPodName(serverName), //
					explorerService.getContainerName(), //
					finalPath //
			);
		}).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(5));
	}

	public void downloadFile(String serverName, String path, OutputStream outputStream) {
		final String finalPath = normalizePath(path);

		fileSystemService.downloadFile( //
				serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME, //
				finalPath //
		).onErrorResume(e -> fileSystemService.downloadFile( //
				serverOptions.getNamespace(), //
				explorerService.getExplorerPodName(serverName), //
				explorerService.getContainerName(), //
				finalPath //
		)).flatMap(inputStream -> Mono.fromCallable(() -> { //
			inputStream.transferTo(outputStream); //
			return null; //
		})).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(60));
	}

	public void uploadFile(String serverName, String path, Resource resource) {
		path = normalizePath(path);
		path = Strings.CS.appendIfMissing(path, "/" + resource.getFilename());

		final String finalPath = path;

		Mono.fromCallable(resource::getInputStream)
				.flatMap(inputStream -> fileSystemService.uploadFile(serverOptions.getNamespace(),
						getPodName(serverName), CONTAINER_NAME, finalPath, inputStream))
				.onErrorResume(e -> Mono.fromCallable(resource::getInputStream)
						.flatMap(inputStream -> fileSystemService.uploadFile(serverOptions.getNamespace(),
								explorerService.getExplorerPodName(serverName), explorerService.getContainerName(),
								finalPath, inputStream)))
				.subscribeOn(Schedulers.boundedElastic()).block(Duration.ofSeconds(60));
	}

	public void createDirectory(String serverName, String path) {
		final String finalPath = normalizePath(path);

		fileSystemService.createDirectory( //
				serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME, //
				finalPath //
		).onErrorResume(e -> {
			return fileSystemService.createDirectory( //
					serverOptions.getNamespace(), //
					explorerService.getExplorerPodName(serverName), //
					explorerService.getContainerName(), //
					finalPath //
			);
		}).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(60));
	}

	public void createEmptyFile(String serverName, String path) {
		final String finalPath = normalizePath(path);

		fileSystemService.touchFile( //
				serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME, //
				finalPath //
		).onErrorResume(e -> {
			return fileSystemService.touchFile( //
					serverOptions.getNamespace(), //
					explorerService.getExplorerPodName(serverName), //
					explorerService.getContainerName(), //
					finalPath //
			);
		}).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(60));
	}

	public void deletePath(String serverName, String path) {
		final String finalPath = normalizePath(path);

		fileSystemService.deletePath( //
				serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME, //
				finalPath //
		).onErrorResume(e -> {
			return fileSystemService.deletePath( //
					serverOptions.getNamespace(), //
					explorerService.getExplorerPodName(serverName), //
					explorerService.getContainerName(), //
					finalPath //
			);
		}).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(60));
	}

	public String getContent(String serverName, String path) {
		final String finalPath = normalizePath(path);

		return fileSystemService.content( //
				serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME, //
				finalPath //
		).onErrorResume(e -> {
			return fileSystemService.deletePath( //
					serverOptions.getNamespace(), //
					explorerService.getExplorerPodName(serverName), //
					explorerService.getContainerName(), //
					finalPath //
			);
		}).subscribeOn(Schedulers.boundedElastic()) //
				.block(Duration.ofSeconds(60));
	}

	// ----- UTIL -----

	private String getPodName(String serverName) {
		return String.format("%s-0", serverName);
	}

	private void validate(ServerInstanceDto server) {
		if (server.getName().equals("console")) {
			throw new ConflictException("Reserved name");
		}
	}

	private static V1ResourceRequirements getRequirements(ServerInstanceDto instance) {
		String memory = String.format("%dM", (int) (instance.getMemory() * 1.15));
		String cpu = String.format("%dm", instance.getCpu());
		return new V1ResourceRequirements() //
				.putLimitsItem("cpu", new Quantity(cpu)) //
				.putLimitsItem("memory", new Quantity(memory));
	}

	private Map<String, String> getLabels(ServerInstanceDto instance) {
		Map<String, String> labels = new HashMap<>(getSelectorLabels(instance));
		labels.put(MinecraftServerLabel.LABEL_SERVER_TYPE, instance.getType().toString().toUpperCase());
		labels.put(MinecraftServerLabel.LABEL_SERVER_CPU, Integer.toString(instance.getCpu()));
		labels.put(MinecraftServerLabel.LABEL_SERVER_MEMORY, Integer.toString(instance.getMemory()));
		labels.put(MinecraftServerLabel.LABEL_SERVER_MINECRAFT_EULA, Boolean.toString(instance.isEula()));
		if (instance.getVersion() != null) {
			labels.put(MinecraftServerLabel.LABEL_SERVER_MINECRAFT_VERSION, instance.getVersion());
		}
		if (instance.getModrinthProjectId() != null) {
			labels.put(MinecraftServerLabel.LABEL_SERVER_MODRINTH_PROJECT_ID, instance.getModrinthProjectId());
		}
		if (instance.getCurseforgePageUrl() != null) {
			labels.put(MinecraftServerLabel.LABEL_SERVER_CURSEFORGE_URL, instance.getCurseforgePageUrl());
		}

		return labels;
	}

	private Map<String, String> getSelectorLabels(ServerInstanceDto instance) {
		Map<String, String> labels = new HashMap<>();
		labels.put(MinecraftServerLabel.LABEL_SERVER_NAME, instance.getName());
		labels.put(MinecraftServerLabel.LABEL_MANAGED_BY, MANAGED_BY);

		return labels;
	}

	private List<ServerInstanceDto> toDtos(List<V1StatefulSet> statefulSets) {
		return statefulSets.stream() //
				.map(this::toDto) //
				.toList();
	}

	private ServerInstanceDto toDto(V1StatefulSet statefulSet) {
		V1ObjectMeta metadata = statefulSet.getMetadata();
		Map<String, String> labels = metadata.getLabels();

		ServerInstanceDto server = new ServerInstanceDto();
		server.setName(labels.get(MinecraftServerLabel.LABEL_SERVER_NAME));
		server.setType(SmartServerTypeDto.valueOf(labels.get(MinecraftServerLabel.LABEL_SERVER_TYPE)));
		server.setCpu(Integer.parseInt((labels.get(MinecraftServerLabel.LABEL_SERVER_CPU))));
		server.setMemory(Integer.parseInt((labels.get(MinecraftServerLabel.LABEL_SERVER_MEMORY))));
		server.setVersion(labels.get(MinecraftServerLabel.LABEL_SERVER_MINECRAFT_VERSION));
		server.setModrinthProjectId(labels.get(MinecraftServerLabel.LABEL_SERVER_MODRINTH_PROJECT_ID));
		server.setCurseforgePageUrl(labels.get(MinecraftServerLabel.LABEL_SERVER_CURSEFORGE_URL));
		server.setEula(Boolean.valueOf(labels.get(MinecraftServerLabel.LABEL_SERVER_MINECRAFT_EULA)));

		return server;
	}

	private String normalizePath(String path) {
		return path = path.replaceAll("^(\\./)|(^/)|^(?!\\./)", "./");
	}
}
