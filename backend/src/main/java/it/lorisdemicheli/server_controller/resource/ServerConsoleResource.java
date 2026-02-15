package it.lorisdemicheli.server_controller.resource;

import org.jboss.resteasy.reactive.RestStreamElementType;
import io.smallrye.mutiny.Multi;
import it.lorisdemicheli.server_controller.service.ServerInstanceService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Path("/api/servers/{serverName}/console")
public class ServerConsoleResource {

  private final ServerInstanceService service;

  @GET
  @Path("/logs")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  public Multi<String> logs(@PathParam("serverName") String serverName) {
    return service.logs(serverName);
  }

  @POST
  @Path("/commands")
  @Consumes(MediaType.TEXT_PLAIN)
  public Response commands(@PathParam("serverName") String serverName, String command) {
    service.sendCommand(serverName, command);
    return Response.accepted().build();
  }
}
