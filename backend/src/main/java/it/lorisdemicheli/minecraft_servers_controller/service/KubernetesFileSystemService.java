package it.lorisdemicheli.minecraft_servers_controller.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import io.kubernetes.client.Copy;
import io.kubernetes.client.Exec;
import io.kubernetes.client.openapi.ApiException;
import it.lorisdemicheli.minecraft_servers_controller.domain.FileEntry;

@Service
public class KubernetesFileSystemService {

  @Autowired
  private Exec exec;
  @Autowired
  private Copy copy;

  // LISTA FILE CON METADATI
  public List<FileEntry> listFiles(String ns, String pod, String container, String path) throws IOException, InterruptedException, ApiException  {
    String cmd = String.format("stat -c '%%n|%%F|%%s|%%Y' %s/*", path);
    String output = executeCommand(ns, pod, container, new String[] {"sh", "-c", cmd});

    if (output.isBlank()) {
      return List.of();
    }

    List<FileEntry> entries = new ArrayList<>();
    for (String line : output.split("\n")) {
      String[] p = line.split("\\|");
      if (partsValid(p)) {
        entries.add(FileEntry.fromStat(p[0], p[1], Long.parseLong(p[2]), Long.parseLong(p[3])));
      }
    }
    return entries;
  }

  public void uploadFile(String ns, String pod, String container, String remotePath,
      InputStream inputStream) throws IOException, ApiException  {
    Path tempFile = Files.createTempFile("k8s-up-", ".tmp");
    try {
      try (inputStream) {
        Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
      }
      copy.copyFileToPod(ns, pod, container, tempFile, Path.of(remotePath));
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  public InputStream downloadFile(String ns, String pod, String container, String remotePath) throws ApiException, IOException {
    return copy.copyFileFromPod(ns, pod, container, remotePath);
  }

  public void createDirectory(String ns, String pod, String container, String path) throws IOException, InterruptedException, ApiException {
    executeCommand(ns, pod, container, new String[] {"mkdir", "-p", path});
  }

  public void deletePath(String ns, String pod, String container, String path) throws IOException, InterruptedException, ApiException  {
    executeCommand(ns, pod, container, new String[] {"rm", "-rf", path});
  }

  public void touchFile(String ns, String pod, String container, String path) throws IOException, InterruptedException, ApiException {
    executeCommand(ns, pod, container, new String[] {"touch", path});
  }

  private String executeCommand(String ns, String pod, String container, String[] command) throws IOException, InterruptedException, ApiException {
    Process proc = exec.exec(ns, pod, command, container, false, false);

    byte[] outBytes;
    byte[] errBytes;

    try (var out = proc.getInputStream(); var err = proc.getErrorStream()) {
      outBytes = out.readAllBytes();
      errBytes = err.readAllBytes();
    }

    if (!proc.waitFor(15, TimeUnit.SECONDS)) {
      proc.destroy();
      throw new IOException("K8s Command Timeout");
    }

    if (proc.exitValue() != 0) {
      String errorMsg = new String(errBytes, StandardCharsets.UTF_8);
      if (errorMsg.contains("No such file"))
        return "";
      throw new IOException("K8s Error (Code " + proc.exitValue() + "): " + errorMsg);
    }

    return new String(outBytes, StandardCharsets.UTF_8).trim();
  }

  private boolean partsValid(String[] p) {
    return p.length == 4;
  }
}
