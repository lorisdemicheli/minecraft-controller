package it.lorisdemicheli.minecraft_servers_controller.exception;

import lombok.experimental.StandardException;

/** Unexpected failure of an operation. HTTP 500; the message must be safe to show to the client. */
@StandardException
public class ServerException extends RuntimeException {
  private static final long serialVersionUID = 1L;
}
