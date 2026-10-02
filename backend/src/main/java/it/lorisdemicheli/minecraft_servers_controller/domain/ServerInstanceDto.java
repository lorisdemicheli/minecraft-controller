package it.lorisdemicheli.minecraft_servers_controller.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ServerInstanceDto {
  private String name;
  private SmartServerTypeDto type;
  private int cpu = 1000; // milli cpu
  private int memory = 1024; // mega byte

  private boolean eula;
  private String version;
  private String modrinthProjectId;
  private String curseforgePageUrl;

  /** Filled by the server on reads; ignored on create/update. */
  @Schema(accessMode = Schema.AccessMode.READ_ONLY)
  private ServerState state;
}
