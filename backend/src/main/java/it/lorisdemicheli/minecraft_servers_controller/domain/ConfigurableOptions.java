package it.lorisdemicheli.minecraft_servers_controller.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConfigurableOptions implements TypeMinecraft {

  
  private String version;
  private String modrinthProjectId;
  private String curseforgePageUrl;
  
  private boolean eula = false;
  private double cpu = 0.5;
  private double memory = 1.024;
}
