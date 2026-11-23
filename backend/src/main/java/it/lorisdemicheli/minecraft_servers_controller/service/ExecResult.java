package it.lorisdemicheli.minecraft_servers_controller.service;

/**
 * Outcome of a command run in a pod.
 *
 * @param truncated stdout or stderr was longer than the configured limit and has been cut
 */
public record ExecResult(int exitCode, String stdout, String stderr, boolean truncated) {

  public ExecResult(int exitCode, String stdout, String stderr) {
    this(exitCode, stdout, stderr, false);
  }

  public boolean ok() {
    return exitCode == 0;
  }
}
