package it.lorisdemicheli.minecraft_servers_controller.exception;

import lombok.experimental.StandardException;

@StandardException
public class ResourceNotFoundException extends RuntimeException {
  private static final long serialVersionUID = 1L;
}