package it.lorisdemicheli.minecraft_servers_controller.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/** Live information. Usage and max values use the same units as {@link ServerInstanceDto}. */
@Getter
@Setter
@RequiredArgsConstructor
public class ServerInstanceInfoDto {
  private final ServerState state;
  /** milli cpu */
  private Long cpuUsage;
  /** mega byte */
  private Long memoryUsage;
  /** milli cpu */
  private Long maxCpu;
  /** mega byte */
  private Long maxMemory;
  private ServerVersionDto version;
  private ServerPopulationDto population;
  private ServerDescriptionDto description;
  private String icon;
}
