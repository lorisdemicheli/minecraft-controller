package it.lorisdemicheli.minecraft_servers_controller.domain;

import java.util.ArrayList;
import java.util.List;
import io.kubernetes.client.openapi.models.V1EnvVar;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum SmartServerTypeDto {
  VANILLA("VANILLA") {
    @Override
    public List<V1EnvVar> specificEnv(ServerInstanceDto instance,
        MinecraftServerOptions serverOptions) {
      List<V1EnvVar> envs = new ArrayList<>();
      envs.add(new V1EnvVar().name("VERSION").value(instance.getVersion()));
      return envs;
    }
  }, //
  PLUGIN("PAPER") {
    @Override
    public List<V1EnvVar> specificEnv(ServerInstanceDto instance,
        MinecraftServerOptions serverOptions) {
      List<V1EnvVar> envs = new ArrayList<>();
      envs.add(new V1EnvVar().name("VERSION").value(instance.getVersion()));
      return envs;
    }
  }, //
  MOD("FORGE") {
    @Override
    public List<V1EnvVar> specificEnv(ServerInstanceDto instance,
        MinecraftServerOptions serverOptions) {
      List<V1EnvVar> envs = new ArrayList<>();
      envs.add(new V1EnvVar().name("VERSION").value(instance.getVersion()));
      return envs;
    }
  }, //
  MODRINTH("MODRINTH") {
    @Override
    public List<V1EnvVar> specificEnv(ServerInstanceDto instance,
        MinecraftServerOptions serverOptions) {
      List<V1EnvVar> envs = new ArrayList<>();
      envs.add(new V1EnvVar().name("MODRINTH_PROJECT").value(instance.getModrinthProjectId()));
      return envs;
    }
  }, //
  CURSEFORGE("AUTO_CURSEFORGE") {
    @Override
    public List<V1EnvVar> specificEnv(ServerInstanceDto instance,
        MinecraftServerOptions serverOptions) {
      List<V1EnvVar> envs = new ArrayList<>();
      envs.add(new V1EnvVar().name("CF_API_KEY").value(serverOptions.getCurseForgeApiKey()));
      envs.add(new V1EnvVar().name("CF_PAGE_URL").value(instance.getCurseforgePageUrl()));
      return envs;
    }
  };

  private final String value;

  protected abstract List<V1EnvVar> specificEnv(ServerInstanceDto instance,
      MinecraftServerOptions serverOptions);

  public List<V1EnvVar> getEnvs(ServerInstanceDto instance, MinecraftServerOptions serverOptions) {
    List<V1EnvVar> envs = new ArrayList<>();
    envs.add(new V1EnvVar().name("TYPE").value(this.value));
    envs.addAll(specificEnv(instance, serverOptions));
    return envs;
  }

}
