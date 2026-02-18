package it.lorisdemicheli.minecraft_servers_controller.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ServerInstanceDto  {

  private String name;
  private SmartServerTypeDto type;
  private int cpu = 1000; // milli cpu
  private int memory = 1024; //mega byte
  
  private boolean eula;
  private String version;
  private String modrinthProjectId;
  private String curseforgePageUrl;
}
