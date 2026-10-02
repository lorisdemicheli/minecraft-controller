package it.lorisdemicheli.minecraft_servers_controller.service;

public record ExecResult(int exitCode, String stdout, String stderr) {
  public boolean ok() {
    return exitCode == 0;
  }
}
