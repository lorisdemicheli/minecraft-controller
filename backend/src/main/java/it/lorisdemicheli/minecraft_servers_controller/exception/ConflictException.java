package it.lorisdemicheli.minecraft_servers_controller.exception;

import lombok.experimental.StandardException;

/** The request is valid but clashes with the current state (server running, name taken...). HTTP 409. */
@StandardException
public class ConflictException extends RuntimeException {
  private static final long serialVersionUID = 1L;
}
