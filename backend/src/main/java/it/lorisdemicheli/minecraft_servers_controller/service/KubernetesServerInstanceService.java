package it.lorisdemicheli.minecraft_servers_controller.service;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.function.FailableRunnable;
import org.apache.commons.lang3.function.FailableSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1ExecAction;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpec;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1Probe;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1StatefulSetList;
import io.kubernetes.client.openapi.models.V1StatefulSetSpec;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import io.kubernetes.client.openapi.models.V1VolumeResourceRequirements;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import it.lorisdemicheli.minecraft_servers_controller.domain.FileEntry;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerInstanceInfoDto;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerState;
import it.lorisdemicheli.minecraft_servers_controller.domain.SmartServerTypeDto;
import it.lorisdemicheli.minecraft_servers_controller.exception.ApiRuntimeException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ConfigurationException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceAlreadyExistsException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ResourceNotFoundException;
import it.lorisdemicheli.minecraft_servers_controller.exception.ServerException;
import jakarta.annotation.Nonnull;
import reactor.core.publisher.Flux;

@Service
public class KubernetesServerInstanceService {

	@Autowired
	private CoreV1Api coreApi;
	@Autowired
	private AppsV1Api appsApi;
	@Autowired
	private KubernetesFileSystemService kubernetesFileSystemService;
	@Autowired
	private KubernetesConsoleService kubernetesConsoleService;
	@Autowired
	private MinecraftServerOptions serverOptions;

	private final static String LABEL_PREFIX = "it.lorisdemicheli/";

	private final static String LABEL_SERVER_NAME = LABEL_PREFIX + "app";
	private final static String LABEL_SERVER_TYPE = LABEL_PREFIX + "server-type";
	private final static String LABEL_SERVER_CPU = LABEL_PREFIX + "cpu";
	private final static String LABEL_SERVER_MEMORY = LABEL_PREFIX + "memory";

	private final static String LABEL_SERVER_MINECRAFT_EULA = LABEL_PREFIX + "eula";
	private final static String LABEL_SERVER_MINECRAFT_VERSION = LABEL_PREFIX + "version";
	private final static String LABEL_SERVER_MODRINTH_PROJECT_ID = LABEL_PREFIX + "modrinth-project-id";
	private final static String LABEL_SERVER_CURSEFORGE_URL = LABEL_PREFIX + "curseforge-url";

	private final static String LABEL_MANAGED_BY = "managed-by";
	private final static String VALUE_MANAGED_BY = "minecraft-controller";

	private final static String CONTAINER_NAME = "minecraft";

	public ServerInstanceDto createServer(@Nonnull ServerInstanceDto instance) {
		if (serverExist(instance.getName())) {
			throw new ResourceAlreadyExistsException();
		}
		validate(instance);

		// 3. Variabili d'Ambiente
		List<V1EnvVar> envs = new ArrayList<>();
		envs.addAll(instance.getType().getEnvs(instance, serverOptions));
		envs.add(new V1EnvVar().name("EULA").value(Boolean.toString(instance.isEula())));
		envs.add(new V1EnvVar().name("CREATE_CONSOLE_IN_PIPE").value("TRUE"));
		envs.add(new V1EnvVar().name("MEMORY").value(String.format("%dM", instance.getMemory())));
		envs.add(new V1EnvVar().name("JVM_OPTS").value("--enable-native-access=ALL-UNNAMED"));

		// 4. Risorse e Probes
		V1Probe healthProbe = new V1Probe().exec(new V1ExecAction().addCommandItem("mc-health")) //
				.initialDelaySeconds(60) //
				.periodSeconds(20);

		V1Probe startupProbe = new V1Probe().exec(new V1ExecAction().addCommandItem("mc-health")) //
				.initialDelaySeconds(30) //
				.periodSeconds(10) //
				.failureThreshold(60);

		Map<String, String> annotations = new HashMap<>();
		String externalDomain = String.format("%s.%s", instance.getName(), serverOptions.getBaseDomain());
		annotations.put("mc-router.itzg.me/externalServerName", externalDomain);

		V1Service service = new V1Service() //
				.metadata(new V1ObjectMeta() //
						.name(instance.getName()) //
						.labels(getSelectorLabels(instance)) //
						.annotations(annotations)) //
				.spec(new V1ServiceSpec() //
						.selector(getSelectorLabels(instance)) //
						.addPortsItem(new V1ServicePort() //
								.protocol("TCP") //
								.port(25565) //
								.targetPort(new IntOrString(25565))));

		// 6. Creazione StatefulSet
		V1StatefulSet statefulSet = new V1StatefulSet() //
				.metadata(new V1ObjectMeta() //
						.name(instance.getName()) //
						.labels(getLabels(instance)))
				.spec(new V1StatefulSetSpec() //
						.serviceName(instance.getName()) //
						.replicas(0) //
						.selector(new V1LabelSelector() //
								.matchLabels(getSelectorLabels(instance)))
						.template(new V1PodTemplateSpec() //
								.metadata(new V1ObjectMeta() //
										.labels(getSelectorLabels(instance)))
								.spec(new V1PodSpec() //
										.addContainersItem(new V1Container() //
												.name(CONTAINER_NAME) //
												.image("itzg/minecraft-server") //
												.env(envs) //
												.resources(getRequirements(instance)) //
												.livenessProbe(healthProbe) //
												.readinessProbe(healthProbe) //
												.startupProbe(startupProbe) //
												.addVolumeMountsItem(new V1VolumeMount() //
														.name("data") //
														.mountPath("/data")))
										.addVolumesItem(new V1Volume() //
												.name("data")
												.persistentVolumeClaim(new V1PersistentVolumeClaimVolumeSource()
														.claimName(instance.getName() + "-pvc"))))));

		// 7. Creazione PVC
		V1PersistentVolumeClaim pvc = new V1PersistentVolumeClaim() //
				.metadata(new V1ObjectMeta() //
						.name(instance.getName() + "-pvc") //
						.labels(getSelectorLabels(instance))) //
				.spec(new V1PersistentVolumeClaimSpec() //
						.accessModes(Arrays.asList("ReadWriteOnce")) //
						.resources(new V1VolumeResourceRequirements() //
								.putRequestsItem("storage", new Quantity("10Gi"))));

		return apiExceptionRetrieve(() -> {
			coreApi //
					.createNamespacedPersistentVolumeClaim(serverOptions.getNamespace(), pvc) //
					.execute();
			coreApi //
					.createNamespacedService(serverOptions.getNamespace(), service) //
					.execute(); //
			V1StatefulSet createdSts = appsApi //
					.createNamespacedStatefulSet(serverOptions.getNamespace(), statefulSet) //
					.execute();

			return transform(createdSts);
		});
	}

	public ServerInstanceDto getServer(String serverName) {
		return apiExceptionRetrieve(() -> {
			V1StatefulSet statefulSet = appsApi.readNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
					.execute();

			return transform(statefulSet);
		});
	}

	public List<ServerInstanceDto> getServerList() {
		return apiExceptionRetrieve(() -> {
			V1StatefulSetList list = appsApi //
					.listNamespacedStatefulSet(serverOptions.getNamespace()) //
					.labelSelector(String.format("%s=%s", LABEL_MANAGED_BY, VALUE_MANAGED_BY)) //
					.execute();

			return list.getItems().stream() //
					.map(this::transform) //
					.toList();
		});
	}

	public ServerInstanceDto updateServer(ServerInstanceDto server) {
		if (!serverExist(server.getName())) {
			throw new ResourceNotFoundException();
		}
		validate(server);

		return apiExceptionRetrieve(() -> {
			V1StatefulSet existingSts = appsApi
					.readNamespacedStatefulSet(server.getName(), serverOptions.getNamespace()).execute();

			existingSts.getMetadata().setLabels(getLabels(server));
			existingSts.getSpec().getSelector().setMatchLabels(getSelectorLabels(server));
			existingSts.getSpec().getTemplate().getMetadata().setLabels(getSelectorLabels(server));

			List<V1EnvVar> envs = server.getType().getEnvs(server, serverOptions);

			envs.add(new V1EnvVar().name("EULA").value(Boolean.toString(server.isEula())));
			envs.add(new V1EnvVar().name("CREATE_CONSOLE_IN_PIPE").value("TRUE"));
			envs.add(new V1EnvVar().name("MEMORY").value(String.format("%dM", server.getMemory())));

			V1Container container = existingSts.getSpec().getTemplate().getSpec().getContainers().get(0);
			container.setEnv(envs);

			container.setResources(getRequirements(server));

			V1StatefulSet updatedSts = appsApi //
					.replaceNamespacedStatefulSet(server.getName(), serverOptions.getNamespace(), existingSts) //
					.execute();

			return transform(updatedSts);
		});
	}

	public boolean deleteServer(String serverName) {
		return apiExceptionRetrieve(() -> {
			appsApi //
					.deleteNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
					.execute();

			coreApi //
					.deleteNamespacedPersistentVolumeClaim(serverName + "-pvc", serverOptions.getNamespace()) //
					.execute();

			coreApi //
					.deleteNamespacedService(serverName, serverOptions.getNamespace()) //
					.execute();

			return true;
		});
	}

	public void startServer(String serverName) {
		apiExceptionRetrieve(() -> {
			V1StatefulSet statefulSet = appsApi //
					.readNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
					.execute();

			statefulSet.getSpec().setReplicas(1);

			appsApi //
					.replaceNamespacedStatefulSet( //
							serverName, //
							serverOptions.getNamespace(), //
							statefulSet) //
					.execute();
		});
	}

	public void stopServer(String serverName) {
		apiExceptionRetrieve(() -> {
			V1StatefulSet statefulSet = appsApi //
					.readNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
					.execute();

			statefulSet.getSpec().setReplicas(0);

			appsApi //
					.replaceNamespacedStatefulSet( //
							serverName, //
							serverOptions.getNamespace(), //
							statefulSet) //
					.execute();
		});
	}

	public void terminateServer(String serverName) {
		apiExceptionRetrieve(() -> {
			V1StatefulSet sts = appsApi //
					.readNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
					.execute();

			sts.getSpec().setReplicas(0);

			appsApi //
					.replaceNamespacedStatefulSet(serverName, serverOptions.getNamespace(), sts) //
					.execute();

			coreApi //
					.deleteNamespacedPod(getPodName(serverName), serverOptions.getNamespace()) //
					.gracePeriodSeconds(0) //
					.execute();

		});
	}

	public ServerInstanceInfoDto getServerInfo(String serverName) {
		return apiExceptionRetrieve(() -> {
			V1StatefulSet sts = appsApi //
					.readNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
					.execute();
			Integer replicas = sts.getSpec().getReplicas();

			if (replicas == null || replicas == 0) {
				return new ServerInstanceInfoDto(ServerState.STOPPED);
			}

			V1Pod pod;
			try {
				pod = coreApi //
						.readNamespacedPod(getPodName(serverName), serverOptions.getNamespace()) //
						.execute();
			} catch (ApiException e) {
				return new ServerInstanceInfoDto(ServerState.STOPPED);
			}

			if (pod.getMetadata().getDeletionTimestamp() != null) {
				return new ServerInstanceInfoDto(ServerState.SHUTDOWN);
			}

			boolean isReady = pod.getStatus().getConditions() //
					.stream().anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));

			if (!isReady) {
				return new ServerInstanceInfoDto(ServerState.STARTING);
			}

			return kubernetesConsoleService.serverInfo(serverOptions.getNamespace(), //
					getPodName(serverName), //
					CONTAINER_NAME, ServerState.RUNNING);
		});
	}

	public Flux<String> streamLogs(String serverName) {
		return kubernetesConsoleService.streamLogs(serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME);
	}

	public List<String> historyLogs(String serverName, int limit, int skip) {
		return kubernetesConsoleService.historyLogs( //
				serverOptions.getNamespace(), //
				getPodName(serverName), //
				CONTAINER_NAME, //
				limit, //
				skip);
	}

	public void sendCommand(String serverName, String command) {
		apiExceptionRetrieve(() -> {
			kubernetesConsoleService.sendMinecraftCommand(serverOptions.getNamespace(), //
					getPodName(serverName), //
					CONTAINER_NAME, //
					command);
		});
	}

	public List<FileEntry> listFiles(String serverName, String path) {
		return apiExceptionRetrieve(() -> {
			return kubernetesFileSystemService //
					.listFiles( //
							serverOptions.getNamespace(), //
							getPodName(serverName), //
							CONTAINER_NAME, //
							Strings.CS.prependIfMissing(path, "."));
		});
	}

	public void downloadFile(String serverName, String path, OutputStream out) {
		apiExceptionRetrieve(() -> {
			kubernetesFileSystemService //
					.downloadFile( //
							serverOptions.getNamespace(), //
							getPodName(serverName), //
							CONTAINER_NAME, //
							Strings.CS.prependIfMissing(path, ".")) //
					.transferTo(out);
		});
	}

	public void uploadFile(String serverName, String destPath, Resource resource) {
		apiExceptionRetrieve(() -> {
			String fullPath = Strings.CS.appendIfMissing(destPath, "/", resource.getFilename());
			kubernetesFileSystemService.uploadFile( //
					serverOptions.getNamespace(), //
					getPodName(serverName), //
					CONTAINER_NAME, //
					fullPath, //
					resource.getInputStream());
		});
	}

	public void createDirectory(String serverName, String path) {
		apiExceptionRetrieve(() -> {
			kubernetesFileSystemService.createDirectory( //
					serverOptions.getNamespace(), //
					getPodName(serverName), //
					CONTAINER_NAME, //
					path);
		});
	}

	public void deletePath(String serverName, String path) {
		apiExceptionRetrieve(() -> {
			kubernetesFileSystemService.deletePath( //
					serverOptions.getNamespace(), //
					getPodName(serverName), //
					CONTAINER_NAME, //
					path);
		});
	}

	public void createEmptyFile(String serverName, String path) {
		apiExceptionRetrieve(() -> {
			kubernetesFileSystemService.touchFile( //
					serverOptions.getNamespace(), //
					getPodName(serverName), //
					CONTAINER_NAME, //
					path);
		});
	}

	private void apiExceptionRetrieve(FailableRunnable<Exception> action) {
		try {
			action.run();
		} catch (ApiException e) {
			if (e.getCode() == 404) {
				throw new ResourceNotFoundException();
			}
			throw new ApiRuntimeException(e);
		} catch (Exception e) {
			throw new RuntimeException("Errore imprevisto", e);
		}
	}

	private <T> T apiExceptionRetrieve(FailableSupplier<T, Exception> action) {
		try {
			return action.get();
		} catch (ApiException e) {
			if (e.getCode() == 404) {
				throw new ResourceNotFoundException();
			}
			throw new ApiRuntimeException(e);
		} catch (Exception e) {
			throw new RuntimeException("Errore imprevisto", e);
		}
	}

	private String getPodName(String serverName) {
		return serverName + "-0";
	}

	private ServerInstanceDto transform(V1StatefulSet statefulSet) {
		V1ObjectMeta metadata = statefulSet.getMetadata();
		Map<String, String> labels = metadata.getLabels();

		ServerInstanceDto server = new ServerInstanceDto();
		server.setName(labels.get(LABEL_SERVER_NAME));
		server.setType(SmartServerTypeDto.valueOf(labels.get(LABEL_SERVER_TYPE)));
		server.setCpu(Integer.parseInt((labels.get(LABEL_SERVER_CPU))));
		server.setMemory(Integer.parseInt((labels.get(LABEL_SERVER_MEMORY))));
		server.setVersion(labels.get(LABEL_SERVER_MINECRAFT_VERSION));
		server.setModrinthProjectId(labels.get(LABEL_SERVER_MODRINTH_PROJECT_ID));
		server.setCurseforgePageUrl(labels.get(LABEL_SERVER_CURSEFORGE_URL));
		server.setEula(Boolean.valueOf(labels.get(LABEL_SERVER_MINECRAFT_EULA)));

		return server;
	}
	
	private V1ResourceRequirements getRequirements(ServerInstanceDto instance) {
		String memory = String.format("%dM", (int) instance.getMemory() * 1.15);
		String cpu = String.format("%dm", instance.getCpu());
		return new V1ResourceRequirements() //
				.putLimitsItem("cpu", new Quantity(cpu)) //
				.putLimitsItem("memory", new Quantity(memory));
	}
	
	private Map<String, String> getLabels(ServerInstanceDto instance) {
		Map<String, String> labels = new HashMap<>(getSelectorLabels(instance));
		labels.put(LABEL_SERVER_TYPE, instance.getType().toString().toUpperCase());
		labels.put(LABEL_SERVER_CPU, Integer.toString(instance.getCpu()));
		labels.put(LABEL_SERVER_MEMORY, Integer.toString(instance.getMemory()));
		labels.put(LABEL_SERVER_MINECRAFT_EULA, Boolean.toString(instance.isEula()));
		if (instance.getVersion() != null) {
			labels.put(LABEL_SERVER_MINECRAFT_VERSION, instance.getVersion());
		}
		if (instance.getModrinthProjectId() != null) {
			labels.put(LABEL_SERVER_MODRINTH_PROJECT_ID, instance.getModrinthProjectId());
		}
		if (instance.getCurseforgePageUrl() != null) {
			labels.put(LABEL_SERVER_CURSEFORGE_URL, instance.getCurseforgePageUrl());
		}
		
		return labels;
	}
	
	private Map<String, String> getSelectorLabels(ServerInstanceDto instance) {
		Map<String, String> labels = new HashMap<>();
		labels.put(LABEL_SERVER_NAME, instance.getName());
		labels.put(LABEL_MANAGED_BY, VALUE_MANAGED_BY);
		
		return labels;
	}

	private boolean serverExist(String serverName) {
		try {
			appsApi //
					.readNamespacedStatefulSet(serverName, serverOptions.getNamespace()) //
					.execute();
			return true;
		} catch (ApiException e) {
			if (e.getCode() == 404) {
				return false;
			}
			throw new ServerException(e);
		}
	}
	
	private void validate(ServerInstanceDto instance) {
		throw new ConfigurationException(
				"Invalid configuration, set one of version, modrinthProjectId curseforgePageUrl");
	}

}
