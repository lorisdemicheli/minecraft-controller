package it.lorisdemicheli.server_controller.dto;

import java.util.ArrayList;
import java.util.List;
import io.fabric8.kubernetes.api.model.EnvVar;
import it.lorisdemicheli.server_controller.config.ServerControllerConfig;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ServerTypeDto {

  // CF_API_KEY
  VANILLA("VANILLA") {
    @Override
    public List<EnvVar> specificEnv(ServerInstanceDto server, ServerControllerConfig serverOptions) {
      List<EnvVar> env = new ArrayList<>();
      env.add(new EnvVar("VERSION", server.getVersion(), null));
      return env;
    }
  }, //
  PLUGIN("PAPER") {
    @Override
    public List<EnvVar> specificEnv(ServerInstanceDto server, ServerControllerConfig serverOptions) {
      List<EnvVar> env = new ArrayList<>();
      env.add(new EnvVar("VERSION", server.getVersion(), null));
      return env;
    }
  }, //
  MOD("FORGE") {
    @Override
    public List<EnvVar> specificEnv(ServerInstanceDto server, ServerControllerConfig serverOptions) {
      List<EnvVar> env = new ArrayList<>();
      env.add(new EnvVar("VERSION", server.getVersion(), null));
      return env;
    }
  }, //
  MODRINTH("MODRINTH") {
    @Override
    public List<EnvVar> specificEnv(ServerInstanceDto server, ServerControllerConfig serverOptions) {
      List<EnvVar> env = new ArrayList<>();
      env.add(new EnvVar("MODRINTH_PROJECT", server.getModrinthProjectId(), null));
      return env;
    }
  }, //
  CURSEFORGE("AUTO_CURSEFORGE") {
    @Override
    public List<EnvVar> specificEnv(ServerInstanceDto server, ServerControllerConfig serverOptions) {
      List<EnvVar> env = new ArrayList<>();
      env.add(new EnvVar("CF_API_KEY", serverOptions.getCurseForgeApiKey(), null));
      env.add(new EnvVar("CF_PAGE_URL", server.getCurseforgePageUrl(), null));
      return env;
    }
  };

  private final String value;

  protected abstract List<EnvVar> specificEnv(ServerInstanceDto server, ServerControllerConfig serverOptions);

  public List<EnvVar> getEnvs(ServerInstanceDto server, ServerControllerConfig serverOptions) {
    List<EnvVar> env = new ArrayList<>();
    // Aggiunge la variabile TYPE comune a tutti
    env.add(new EnvVar("TYPE", this.value, null));
    // Aggiunge le variabili specifiche
    env.addAll(specificEnv(server, serverOptions));
    return env;
  }

}