package it.lorisdemicheli.minecraft_servers_controller.service;

import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1StatefulSetStatus;
import it.lorisdemicheli.minecraft_servers_controller.domain.ServerState;

/** Derives a server's state from its StatefulSet alone: no pod lookup, no exec. */
public final class ServerStates {

  private ServerStates() {}

  public static ServerState of(V1StatefulSet sts) {
    Integer desiredReplicas = sts.getSpec() == null ? null : sts.getSpec().getReplicas();
    int desired = desiredReplicas == null ? 1 : desiredReplicas;
    V1StatefulSetStatus status = sts.getStatus();
    int current = status == null || status.getReplicas() == null ? 0 : status.getReplicas();
    int ready = status == null || status.getReadyReplicas() == null ? 0 : status.getReadyReplicas();

    if (desired == 0) {
      return current == 0 ? ServerState.STOPPED : ServerState.SHUTDOWN;
    }
    return ready >= 1 ? ServerState.RUNNING : ServerState.STARTING;
  }
}
