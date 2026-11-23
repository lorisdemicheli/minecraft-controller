package it.lorisdemicheli.minecraft_servers_controller.exception;

import lombok.experimental.StandardException;

/** HTTP 404. */
@StandardException
public class ResourceNotFoundException extends RuntimeException {
  private static final long serialVersionUID = 1L;
}