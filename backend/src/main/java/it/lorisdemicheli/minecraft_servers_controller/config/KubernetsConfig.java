package it.lorisdemicheli.minecraft_servers_controller.config;

import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.kubernetes.client.Exec;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.Config;

@Configuration
public class KubernetsConfig {

  /**
   * In the cluster the service account is used; {@code kubernetes.client.file} points to a
   * kubeconfig for local development. Every call gets connect/read/write timeouts, so that a
   * hanging API server cannot block a request forever.
   */
  @Bean
  ApiClient apiClient(KubernetesClientConfig cfg, MinecraftServerOptions options)
      throws IOException {
    ApiClient client =
        cfg.getFile() == null ? Config.defaultClient() : Config.fromConfig(cfg.getFile());
    var timeouts = options.getKubernetes();
    client.setConnectTimeout((int) timeouts.getConnectTimeout().toMillis());
    client.setReadTimeout((int) timeouts.getReadTimeout().toMillis());
    client.setWriteTimeout((int) timeouts.getWriteTimeout().toMillis());
    return client;
  }

  @Bean
  CoreV1Api coreV1Api(ApiClient apiClient) {
    return new CoreV1Api(apiClient);
  }

  @Bean
  AppsV1Api appsV1Api(ApiClient apiClient) {
    return new AppsV1Api(apiClient);
  }

  @Bean
  Exec exec(ApiClient apiClient) {
    return new Exec(apiClient);
  }
}
