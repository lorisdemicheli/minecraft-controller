package it.lorisdemicheli.minecraft_servers_controller.domain;

import java.util.ArrayList;
import java.util.List;
import io.kubernetes.client.openapi.models.V1EnvVar;
import it.lorisdemicheli.minecraft_servers_controller.config.MinecraftServerOptions;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum Type {
  

  // CF_API_KEY
  VANILLA("VANILLA") {
    @Override
    public List<V1EnvVar> specificEnv(TypeMinecraft options, MinecraftServerOptions serverOptions) {
      List<V1EnvVar> env = new ArrayList<>();
      env.add(new V1EnvVar().name("VERSION").value(options.getVersion()));
      return env;
    }
  }, //
  PLUGIN("PAPER") {
    @Override
    public List<V1EnvVar> specificEnv(TypeMinecraft options, MinecraftServerOptions serverOptions) {
      List<V1EnvVar> env = new ArrayList<>();
      env.add(new V1EnvVar().name("VERSION").value(options.getVersion()));
      return env;
    }
  }, //
  MOD("FORGE") {
    @Override
    public List<V1EnvVar> specificEnv(TypeMinecraft options, MinecraftServerOptions serverOptions) {
      List<V1EnvVar> env = new ArrayList<>();
      env.add(new V1EnvVar().name("VERSION").value(options.getVersion()));
      return env;
    }
  }, //
  MODRINTH("MODRINTH") {
    @Override
    public List<V1EnvVar> specificEnv(TypeMinecraft options, MinecraftServerOptions serverOptions) {
      List<V1EnvVar> env = new ArrayList<>();
      env.add(new V1EnvVar().name("MODRINTH_PROJECT").value(options.getModrinthProjectId()));
      return env;
    }
  }, //
  CURSEFORGE("AUTO_CURSEFORGE") {
    @Override
    public List<V1EnvVar> specificEnv(TypeMinecraft options, MinecraftServerOptions serverOptions) {
      List<V1EnvVar> env = new ArrayList<>();
      env.add(new V1EnvVar().name("CF_API_KEY").value(serverOptions.getCurseForgeApiKey()));
      env.add(new V1EnvVar().name("CF_PAGE_URL").value(options.getCurseforgePageUrl()));
      return env;
    }
  };

  private final String value;

  protected abstract List<V1EnvVar> specificEnv(TypeMinecraft options, MinecraftServerOptions serverOptions);

  public List<V1EnvVar> createEnv(TypeMinecraft options, MinecraftServerOptions serverOptions) {
    List<V1EnvVar> env = new ArrayList<>();
    env.add(new V1EnvVar().name("TYPE").value(this.value));
    env.addAll(specificEnv(options, serverOptions));
    return env;
  }

}
