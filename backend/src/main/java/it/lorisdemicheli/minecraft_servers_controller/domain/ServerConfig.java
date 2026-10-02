package it.lorisdemicheli.minecraft_servers_controller.domain;

/** Everything the user chose for a server. Stored as JSON in one annotation of the StatefulSet. */
public record ServerConfig(SmartServerTypeDto type, int cpu, int memory, boolean eula,
    String version, String modrinthProjectId, String curseforgePageUrl) {

  public static ServerConfig from(ServerInstanceDto dto) {
    return new ServerConfig(dto.getType(), dto.getCpu(), dto.getMemory(), dto.isEula(),
        dto.getVersion(), dto.getModrinthProjectId(), dto.getCurseforgePageUrl());
  }

  public ServerInstanceDto toDto(String name) {
    ServerInstanceDto dto = new ServerInstanceDto();
    dto.setName(name);
    dto.setType(type);
    dto.setCpu(cpu);
    dto.setMemory(memory);
    dto.setEula(eula);
    dto.setVersion(version);
    dto.setModrinthProjectId(modrinthProjectId);
    dto.setCurseforgePageUrl(curseforgePageUrl);
    return dto;
  }
}
