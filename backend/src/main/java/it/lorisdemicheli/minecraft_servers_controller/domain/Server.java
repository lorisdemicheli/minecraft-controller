package it.lorisdemicheli.minecraft_servers_controller.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Server {

  private String id;
  private Type type;
  private String name;
  private String memory;
  private String version;
  private Integer modpackId;
  private String modpackUrl;
  private String modpackName;
}
