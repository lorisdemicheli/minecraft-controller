package it.lorisdemicheli.server_controller.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServerInstanceDto {

  private String name;
  private ServerTypeDto type;
  private int cpu = 500;
  private int memory = 1024;
  private boolean eula = false;

  private String version;
  private String modrinthProjectId;
  private String curseforgePageUrl;
}