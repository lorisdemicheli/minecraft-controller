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

  @Bean
  ApiClient apiClient(KubernetesClientConfig cfg) throws IOException {
    if (cfg.getFile() == null) {
      return Config.defaultClient();
    } else {
      return Config.fromConfig(cfg.getFile());
    }
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
