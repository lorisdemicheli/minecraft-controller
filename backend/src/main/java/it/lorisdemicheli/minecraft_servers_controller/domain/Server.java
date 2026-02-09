package it.lorisdemicheli.minecraft_servers_controller.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Server implements TypeMinecraft {

  private String name;
  private Type type;
  private double cpu;
  private double memory;
  private boolean eula;

  private String version;
  private String modrinthProjectId;
  private String curseforgePageUrl;
}
