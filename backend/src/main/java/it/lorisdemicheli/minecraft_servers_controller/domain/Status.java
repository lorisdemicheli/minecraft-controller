package it.lorisdemicheli.minecraft_servers_controller.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Status {

  private double cpu; 
  private double ram;
  private int onlinePlayers;
  private int maxPlayers;
}
