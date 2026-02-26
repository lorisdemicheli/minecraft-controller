package it.lorisdemicheli.minecraft_servers_controller.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import io.kubernetes.client.Copy;
import it.lorisdemicheli.minecraft_servers_controller.domain.FileEntry;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class KubernetesFileSystemService {

  private final Copy copy;
  private final KubernetesAsyncService kubernetesService;

  public Mono<List<FileEntry>> getFiles(String namespace, String pod, String container,
      String path) {
    String cmd = String.format("stat -c '%%n|%%F|%%s|%%Y' %s/*", path);
    return kubernetesService.execCommand(namespace, pod, container, new String[] {"sh", "-c", cmd})
        .map(output -> {
          List<FileEntry> entries = new ArrayList<>();
          for (String line : output.split("\n")) {
            String[] p = line.split("\\|");
            if (partsValid(p)) {
              entries
                  .add(FileEntry.fromStat(p[0], p[1], Long.parseLong(p[2]), Long.parseLong(p[3])));
            }
          }
          return entries;
        });
  }

  public Mono<Void> uploadFile(String namespace, String pod, String container, String path,
      InputStream inputStream) {
    return Mono.using(() -> Files.createTempFile("k8s-up-", ".tmp"),
        tempFile -> Mono.fromCallable(() -> {
          try (inputStream) {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
          }
          copy.copyFileToPod(namespace, pod, container, tempFile, Path.of(path));
          return null;
        }), tempFile -> {
          try {
            Files.deleteIfExists(tempFile);
          } catch (IOException e) {
          }
        });
  }

  public Mono<InputStream> downloadFile(String namespace, String pod, String container,
      String path) {
    return Mono.fromCallable(() -> {
      return copy.copyFileFromPod(namespace, pod, container, path);
    });
  }

  public Mono<String> createDirectory(String namespace, String pod, String container, String path) {
    return kubernetesService.execCommand(namespace, pod, container,
        new String[] {"sh", "-c", "mkdir", "-p", path});
  }

  public Mono<String> deletePath(String namespace, String pod, String container, String path) {
    return kubernetesService.execCommand(namespace, pod, container,
        new String[] {"sh", "-c", "rm", "-rf", path});
  }

  public Mono<String> touchFile(String namespace, String pod, String container, String path) {
    return kubernetesService.execCommand(namespace, pod, container,
        new String[] {"sh", "-c", "touch", path});
  }
  
  public Mono<String> content(String namespace, String pod, String container, String path) {
	  return kubernetesService.execCommand(namespace, pod, container,
		        new String[] {"cat", path});
  }

  private boolean partsValid(String[] p) {
    return p.length == 4;
  }
}
