package it.lorisdemicheli.server_controller.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServerInstanceInfoDto {
  private ServerStateDto state;
  private Version version;
  private Players players;
  private Description description;
  private String favicon;

  @Getter
  @Setter
  public static class Version {
    private String name;
    private int protocol;
  }

  @Getter
  @Setter
  public static class Players {
    private int max;
    private int online;
    @JsonProperty("Sample")
    private List<Player> sample;
  }
  
  @Getter
  @Setter
  private static class Player {
    private String id;
    private String name;
  }

  @Getter
  @Setter
  public static class Description {
    private String text;
    private boolean bold;
    private boolean italic;
    private boolean underlined;
    private boolean strikethrough;
    private boolean obfuscated;
    private String color;
    private Object extra;
  }
}