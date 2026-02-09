//package it.lorisdemicheli.minecraft_servers_controller.service;
//
//import java.io.BufferedReader;
//import java.io.File;
//import java.io.InputStream;
//import java.io.InputStreamReader;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.nio.file.Paths;
//import java.nio.file.StandardCopyOption;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//import java.util.stream.Collectors;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//import io.kubernetes.client.Copy;
//import io.kubernetes.client.Exec;
//import io.kubernetes.client.PodLogs;
//import io.kubernetes.client.openapi.ApiClient;
//import io.kubernetes.client.openapi.ApiException;
//import io.kubernetes.client.openapi.apis.CoreV1Api;
//import io.kubernetes.client.openapi.models.V1Container;
//import io.kubernetes.client.openapi.models.V1EnvVar;
//import io.kubernetes.client.openapi.models.V1ObjectMeta;
//import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
//import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpec;
//import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource;
//import io.kubernetes.client.openapi.models.V1Pod;
//import io.kubernetes.client.openapi.models.V1PodSpec;
//import io.kubernetes.client.openapi.models.V1Service;
//import io.kubernetes.client.openapi.models.V1ServicePort;
//import io.kubernetes.client.openapi.models.V1ServiceSpec;
//import io.kubernetes.client.openapi.models.V1Volume;
//import io.kubernetes.client.openapi.models.V1VolumeMount;
//import io.kubernetes.client.openapi.models.V1VolumeResourceRequirements;
//import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
//import it.lorisdemicheli.minecraft_servers_controller.domain.Server;
//import it.lorisdemicheli.minecraft_servers_controller.domain.Type;
//import reactor.core.publisher.Flux;
//import reactor.core.scheduler.Schedulers;
//
//@Service
//public class ServerService {
//
//  @Autowired
//  private CoreV1Api coreApi;
//  @Autowired
//  private ApiClient apiClient;
//  @Autowired
//  private PodLogs podLogs;
//  @Autowired
//  private MinecraftServerOptions options;
//
//  private static final String LABEL_TYPE_KEY = "type";
//  private static final String LABEL_TYPE_VAL = "minecraft-server";
//  private static final String LABEL_ID = "mc-server-id";
//
//  // --- 1. CREAZIONE (Setup PVC con Annotazioni) ---
//  public Server createServer(Server server) {
//    String id = server.getName().toLowerCase().replaceAll("[^a-z0-9]", "-");
//    server.setId(id);
//
//    try {
//      // Preparo le annotazioni (il nostro "Database")
//      V1ObjectMeta metadata = new V1ObjectMeta().name("pvc-" + id)
//          .putLabelsItem(LABEL_TYPE_KEY, LABEL_TYPE_VAL).putLabelsItem(LABEL_ID, id)
//          // Salvataggio dati persistenti
//          .putAnnotationsItem("mc/name", server.getName())
//          .putAnnotationsItem("mc/type", server.getType().name())
//          .putAnnotationsItem("mc/memory", server.getMemory())
//          .putAnnotationsItem("mc/version", server.getVersion());
//
//      if (server.getModpackUrl() != null)
//        metadata.putAnnotationsItem("mc/modpackUrl", server.getModpackUrl());
//      if (server.getModpackId() != null)
//        metadata.putAnnotationsItem("mc/modpackId", server.getModpackId().toString());
//
//      V1PersistentVolumeClaim pvc = new V1PersistentVolumeClaim().metadata(metadata)
//          .spec(new V1PersistentVolumeClaimSpec().accessModes(List.of("ReadWriteOnce"))
//              .resources(new V1VolumeResourceRequirements().putRequestsItem("storage",
//                  new io.kubernetes.client.custom.Quantity("10Gi"))));
//
//      coreApi.createNamespacedPersistentVolumeClaim(options.getNamespace(), pvc).execute();
//      createServiceK8s(server);
//
//      // Avvio automatico dopo la creazione
//      startServer(id);
//      server.setStatus("STARTING");
//      return server;
//
//    } catch (ApiException e) {
//      throw new RuntimeException("Errore creazione server: " + e.getResponseBody(), e);
//    }
//  }
//
//  // --- 2. AVVIO (Legge PVC -> Crea Pod) ---
//  public void startServer(String id) {
//    try {
//      if (isPodActive(id))
//        return; // Già attivo
//
//      // Recupera configurazione dal PVC
//      V1PersistentVolumeClaim pvc = coreApi
//          .readNamespacedPersistentVolumeClaim("pvc-" + id, options.getNamespace()).execute();
//      Map<String, String> ann = pvc.getMetadata().getAnnotations();
//
//      // Ricostruisco l'oggetto Server dai metadati
//      Server server = new Server();
//      server.setId(id);
//      server.setMemory(ann.getOrDefault("mc/memory", "2G"));
//      server.setType(Type.valueOf(ann.getOrDefault("mc/type", "VANILLA")));
//      server.setVersion(ann.getOrDefault("mc/version", "latest"));
//      server.setModpackUrl(ann.get("mc/modpackUrl"));
//      if (ann.get("mc/modpackId") != null)
//        server.setModpackId(Integer.parseInt(ann.get("mc/modpackId")));
//
//      // Preparo variabili d'ambiente
//      List<V1EnvVar> envVars = new ArrayList<>();
//      envVars.add(new V1EnvVar().name("EULA").value("TRUE"));
//      envVars.add(new V1EnvVar().name("MEMORY").value(server.getMemory()));
//      envVars.addAll(server.getType().getSpecificEnvVars(server, options.getCurseForgeApiKey()));
//
//      // Creazione POD (Naked Pod)
//      V1Pod pod = new V1Pod()
//          .metadata(new V1ObjectMeta().name("pod-" + id)
//              .putLabelsItem(LABEL_TYPE_KEY, LABEL_TYPE_VAL).putLabelsItem(LABEL_ID, id))
//          .spec(new V1PodSpec().restartPolicy("Never") // Se fai /stop muore e resta fermo
//              .addContainersItem(new V1Container().name("minecraft").image("itzg/minecraft-server")
//                  .tty(true).stdin(true) // Fondamentali per i comandi
//                  .env(envVars)
//                  .addVolumeMountsItem(new V1VolumeMount().name("data").mountPath("/data")))
//              .addVolumesItem(new V1Volume().name("data").persistentVolumeClaim(
//                  new V1PersistentVolumeClaimVolumeSource().claimName("pvc-" + id))));
//
//      coreApi.createNamespacedPod(options.getNamespace(), pod).execute();
//
//    } catch (ApiException e) {
//      throw new RuntimeException("Errore start server: " + e.getResponseBody(), e);
//    }
//  }
//
//  // --- 3. STOP (Elimina Pod) ---
//  public void stopServer(String id) {
//    try {
//      coreApi.deleteNamespacedPod("pod-" + id, options.getNamespace()).execute();
//    } catch (ApiException e) {
//      // Ignora se non esiste (404)
//      if (e.getCode() != 404)
//        throw new RuntimeException("Errore stop", e);
//    }
//  }
//
//  // --- 4. LISTING (Combina PVC e Pod status) ---
//  public List<Server> getAllServers() {
//    List<Server> servers = new ArrayList<>();
//    try {
//      // Prendo tutti i PVC (i server "registrati")
//      var pvcList = coreApi.listNamespacedPersistentVolumeClaim(options.getNamespace())
//          .labelSelector(LABEL_TYPE_KEY + "=" + LABEL_TYPE_VAL).execute();
//
//      // Prendo tutti i Pod (i server "accesi")
//      var podList = coreApi.listNamespacedPod(options.getNamespace())
//          .labelSelector(LABEL_TYPE_KEY + "=" + LABEL_TYPE_VAL).execute();
//
//      Map<String, V1Pod> activePods = podList.getItems().stream()
//          .collect(Collectors.toMap(p -> p.getMetadata().getLabels().get(LABEL_ID), p -> p));
//
//      for (var pvc : pvcList.getItems()) {
//        Map<String, String> ann = pvc.getMetadata().getAnnotations();
//        String id = pvc.getMetadata().getLabels().get(LABEL_ID);
//
//        Server s = new Server();
//        s.setId(id);
//        s.setName(ann.getOrDefault("mc/name", id));
//        s.setMemory(ann.getOrDefault("mc/memory", "2G"));
//        s.setType(Type.valueOf(ann.getOrDefault("mc/type", "VANILLA")));
//        s.setVersion(ann.get("mc/version"));
//        // ... altri campi modpack opzionali ...
//
//        // Calcolo stato
//        if (activePods.containsKey(id)) {
//          V1Pod p = activePods.get(id);
//          boolean ready = p.getStatus().getContainerStatuses() != null
//              && p.getStatus().getContainerStatuses().stream().allMatch(cs -> cs.getReady());
//          s.setStatus(ready ? "ONLINE" : "STARTING");
//        } else {
//          s.setStatus("OFFLINE");
//        }
//        servers.add(s);
//      }
//    } catch (ApiException e) {
//      e.printStackTrace();
//    }
//    return servers;
//  }
//
//  // --- 5. COMANDI & FILE ---
//
//  public void sendCommand(String id, String command) {
//    Exec exec = new Exec(apiClient);
//    try {
//      // mc-send-to-console gestisce tutto l'input successivo come comando
//      String[] cmd = {"mc-send-to-console", command};
//      Process proc = exec.exec(options.getNamespace(), "pod-" + id, cmd, "minecraft", false, true);
//      proc.waitFor();
//    } catch (Exception e) {
//      throw new RuntimeException("Err exec", e);
//    }
//  }
//
//  public List<String> listFiles(String id, String path) {
//    Exec exec = new Exec(apiClient);
//    List<String> res = new ArrayList<>();
//    try {
//      // 'ls -p' aggiunge '/' alle directory
//      Process proc = exec.exec(options.getNamespace(), "pod-" + id,
//          new String[] {"ls", "-1", "-p", path}, "minecraft", false, false);
//      try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
//        String line;
//        while ((line = br.readLine()) != null)
//          res.add(line);
//      }
//      proc.waitFor();
//    } catch (Exception e) {
//      throw new RuntimeException("Err list files", e);
//    }
//    return res;
//  }
//
//  public InputStream downloadFile(String id, String path) {
//    Copy copy = new Copy(apiClient);
//    try {
//      // Ritorna un TAR stream
//      return copy.copyFileFromPod(options.getNamespace(), "pod-" + id, "minecraft", path);
//    } catch (Exception e) {
//      throw new RuntimeException("Err download", e);
//    }
//  }
//
//  public void uploadFile(String id, String destPath, InputStream data) {
//    Copy copy = new Copy(apiClient);
//    File tempFile = null;
//    try {
//      // 1. Creiamo un file temporaneo locale perché la libreria Copy
//      // spesso richiede un file fisico per calcolare i metadati del trasferimento
//      tempFile = File.createTempFile("mc-upload-", ".tmp");
//      Files.copy(data, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
//
//      // 2. Eseguiamo l'upload
//      copy.copyFileToPod(options.getNamespace(), "pod-" + id, "minecraft", tempFile.toPath(),
//          Paths.get(destPath));
//
//    } catch (Exception e) {
//      throw new RuntimeException("Errore durante l'upload sul pod " + id, e);
//    } finally {
//      if (tempFile != null && tempFile.exists()) {
//        tempFile.delete();
//      }
//    }
//  }
//
//  /**
//   * Streaming dei log in tempo reale. Utilizza Project Reactor (Flux) per mantenere la connessione
//   * aperta finché il client (es. browser tramite SSE o WebSocket) è connesso.
//   */
//  public Flux<String> logs(String id) {
//    return Flux.<String>create(sink -> {
//      BufferedReader reader = null;
//      try {
//        // Con il sistema Naked Pod, il nome è prevedibile
//        String podName = "pod-" + id;
//        String containerName = "minecraft";
//
//        // Apriamo lo stream dai log di Kubernetes
//        // follow: true -> continua a leggere le nuove righe
//        // tailLines: 100 -> recupera le ultime 100 righe all'apertura
//        InputStream is = podLogs.streamNamespacedPodLog(options.getNamespace(), podName,
//            containerName, null, 100, true);
//
//        reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
//        String line;
//
//        // Leggiamo riga per riga finché il client non chiude la connessione
//        while (!sink.isCancelled() && (line = reader.readLine()) != null) {
//          sink.next(line);
//        }
//
//        sink.complete();
//
//      } catch (Exception e) {
//        // Se il pod non esiste o è offline, inviamo l'errore al sink
//        if (!sink.isCancelled()) {
//          sink.error(new RuntimeException(
//              "Impossibile leggere i log: il server potrebbe essere offline.", e));
//        }
//      } finally {
//        // Chiudiamo il reader per liberare risorse (memory leak prevention)
//        try {
//          if (reader != null)
//            reader.close();
//        } catch (Exception ignored) {
//        }
//      }
//    }).subscribeOn(Schedulers.boundedElastic());
//  }
//
//  // --- HELPER ---
//
//  private void createServiceK8s(Server server) throws ApiException {
//    String vhost = server.getName().toLowerCase() + "." + options.getBaseDomain();
//    V1Service svc = new V1Service()
//        .metadata(new V1ObjectMeta().name("svc-" + server.getId())
//            .putLabelsItem(LABEL_TYPE_KEY, LABEL_TYPE_VAL)
//            .putAnnotationsItem("minekube.io/virtual-host", vhost))
//        .spec(new V1ServiceSpec().putSelectorItem(LABEL_ID, server.getId())
//            .addPortsItem(new V1ServicePort().port(25565)
//                .targetPort(new io.kubernetes.client.custom.IntOrString(25565))));
//    coreApi.createNamespacedService(options.getNamespace(), svc).execute();
//  }
//
//  private boolean isPodActive(String id) {
//    try {
//      coreApi.readNamespacedPod("pod-" + id, options.getNamespace()).execute();
//      return true;
//    } catch (ApiException e) {
//      return false;
//    }
//  }
//}
