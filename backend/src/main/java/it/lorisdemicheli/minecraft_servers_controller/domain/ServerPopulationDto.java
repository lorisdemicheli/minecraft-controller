package it.lorisdemicheli.minecraft_servers_controller.domain;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServerPopulationDto {
  private int online;
  private int max;
  private List<PlayerDto> players;
}
