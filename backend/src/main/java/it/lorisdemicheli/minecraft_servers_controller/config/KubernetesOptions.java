package it.lorisdemicheli.minecraft_servers_controller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "kubernetes")
public class KubernetesOptions {

  private String namespace = "default";

}
