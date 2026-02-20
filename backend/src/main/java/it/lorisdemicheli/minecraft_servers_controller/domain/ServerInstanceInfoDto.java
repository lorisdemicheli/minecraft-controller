package it.lorisdemicheli.minecraft_servers_controller.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@RequiredArgsConstructor
public class ServerInstanceInfoDto {
  private final ServerState state;
  private ServerVersionDto version;
  private ServerPopulationDto population;
  private ServerDescriptionDto description;
  private String icon;
}
