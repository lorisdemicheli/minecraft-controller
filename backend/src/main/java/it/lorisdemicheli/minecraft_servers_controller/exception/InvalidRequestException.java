package it.lorisdemicheli.minecraft_servers_controller.exception;

import lombok.experimental.StandardException;

/** Request that can never succeed as sent (bad name, bad path, ...). Mapped to HTTP 400. */
@StandardException
public class InvalidRequestException extends RuntimeException {
  private static final long serialVersionUID = 1L;
}
