package it.lorisdemicheli.server_controller.dto;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import io.fabric8.kubernetes.api.model.EnvVar;
import it.lorisdemicheli.server_controller.config.ServerControllerConfig;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Response.Status;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ServerTypeDto {

  // CF_API_KEY
  VANILLA("VANILLA") {
    @Override
    public List<EnvVar> specificEnv(ServerInstanceDto server,
        ServerControllerConfig serverOptions) {
      List<EnvVar> env = new ArrayList<>();
      env.add(new EnvVar("VERSION", server.getVersion(), null));
      return env;
    }

    @Override
    public void validate(ServerInstanceDto server) {
      if (StringUtils.isEmpty(server.getVersion())) {
        throw new BadRequestException("VANILLA need Version");
      }
    }
  }, //
  PLUGIN("PAPER") {
    @Override
    public List<EnvVar> specificEnv(ServerInstanceDto server,
        ServerControllerConfig serverOptions) {
      List<EnvVar> env = new ArrayList<>();
      env.add(new EnvVar("VERSION", server.getVersion(), null));
      return env;
    }

    @Override
    public void validate(ServerInstanceDto server) {
      if (StringUtils.isEmpty(server.getVersion())) {
        throw new BadRequestException("PLUGIN need Version");
      }
    }
  }, //
  MOD("FORGE") {
    @Override
    public List<EnvVar> specificEnv(ServerInstanceDto server,
        ServerControllerConfig serverOptions) {
      List<EnvVar> env = new ArrayList<>();
      env.add(new EnvVar("VERSION", server.getVersion(), null));
      return env;
    }

    @Override
    public void validate(ServerInstanceDto server) {
      if (StringUtils.isEmpty(server.getVersion())) {
        throw new BadRequestException("MOD need Version");
      }
    }
  }, //
  MODRINTH("MODRINTH") {
    @Override
    public List<EnvVar> specificEnv(ServerInstanceDto server,
        ServerControllerConfig serverOptions) {
      List<EnvVar> env = new ArrayList<>();
      env.add(new EnvVar("MODRINTH_PROJECT", server.getModrinthProjectId(), null));
      return env;
    }

    @Override
    public void validate(ServerInstanceDto server) {
      if (StringUtils.isEmpty(server.getModrinthProjectId())) {
        throw new BadRequestException("MODRINTH need ModrinthProjectId");
      }
    }
  }, //
  CURSEFORGE("AUTO_CURSEFORGE") {
    @Override
    public List<EnvVar> specificEnv(ServerInstanceDto server,
        ServerControllerConfig serverOptions) {
      List<EnvVar> env = new ArrayList<>();
      env.add(new EnvVar("CF_API_KEY",
          serverOptions.getCurseForgeApiKey()
              .orElseThrow(() -> new ServerErrorException("CurseForgeApiKey not present",
                  Status.INTERNAL_SERVER_ERROR)),
          null));
      env.add(new EnvVar("CF_PAGE_URL", server.getCurseforgePageUrl(), null));
      return env;
    }

    @Override
    public void validate(ServerInstanceDto server) {
      if (StringUtils.isEmpty(server.getCurseforgePageUrl())) {
        throw new BadRequestException("CURSEFORGE need CurseforgePageUrl");
      }
    }
  };

  private final String value;

  protected abstract List<EnvVar> specificEnv(ServerInstanceDto server,
      ServerControllerConfig serverOptions);

  public abstract void validate(ServerInstanceDto server);

  public List<EnvVar> getEnvs(ServerInstanceDto server, ServerControllerConfig serverOptions) {
    List<EnvVar> env = new ArrayList<>();
    env.add(new EnvVar("TYPE", this.value, null));
    env.add(new EnvVar("EULA", Boolean.toString(server.isEula()).toUpperCase(), null));
    env.addAll(specificEnv(server, serverOptions));
    return env;
  }

}
