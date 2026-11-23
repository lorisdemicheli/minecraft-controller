package it.lorisdemicheli.minecraft_servers_controller.exception;

import lombok.experimental.StandardException;

/**
 * The Kubernetes API did not answer, or kept answering with a transient error (429, 5xx, timeout)
 * after the retries. Mapped to HTTP 503: the same request can succeed later.
 */
@StandardException
public class KubernetesUnavailableException extends ServerException {
  private static final long serialVersionUID = 1L;
}
