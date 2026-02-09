package it.lorisdemicheli.minecraft_servers_controller.domain;

import java.util.Objects;
import java.util.stream.Stream;

public interface TypeMinecraft {

  public String getVersion();

  public void setVersion(String version);

  public String getModrinthProjectId();

  public void setModrinthProjectId(String modrinthProjectId);

  public String getCurseforgePageUrl();

  public void setCurseforgePageUrl(String curseforgePageUrl);
  
  public boolean isEula();
  
  public void setEula(boolean val);
  
  public default boolean isValid() {
    return Stream.of(getVersion(), getModrinthProjectId(), getCurseforgePageUrl()) //
        .filter(Objects::nonNull) //
        .count() == 1;
  }
}
