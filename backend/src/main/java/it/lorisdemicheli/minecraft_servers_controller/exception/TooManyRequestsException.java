package it.lorisdemicheli.minecraft_servers_controller.exception;

import lombok.experimental.StandardException;

/** Mapped to HTTP 429. */
@StandardException
public class TooManyRequestsException extends RuntimeException {
  private static final long serialVersionUID = 1L;
}
