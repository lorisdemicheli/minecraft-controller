package it.lorisdemicheli.minecraft_servers_controller.exception;

import lombok.experimental.StandardException;

/** HTTP 409. */
@StandardException
public class ResourceAlreadyExistsException extends RuntimeException {
  private static final long serialVersionUID = 1L;
}
