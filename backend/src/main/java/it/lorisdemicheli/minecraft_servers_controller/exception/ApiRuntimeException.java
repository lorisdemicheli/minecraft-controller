package it.lorisdemicheli.minecraft_servers_controller.exception;

import io.kubernetes.client.openapi.ApiException;
import lombok.experimental.StandardException;

@StandardException
public class ApiRuntimeException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  
  public ApiRuntimeException(ApiException e) {
    super(e);
  }
}
