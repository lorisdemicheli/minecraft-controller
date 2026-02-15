package it.lorisdemicheli.server_controller.resource;

import java.net.URI;
import java.util.List;
import it.lorisdemicheli.server_controller.dto.ServerInstanceInfoDto;
import it.lorisdemicheli.server_controller.dto.ServerInstanceDto;
import it.lorisdemicheli.server_controller.service.ServerInstanceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.RequiredArgsConstructor;

@Path("/api/servers")
@RequiredArgsConstructor
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServerInstanceController {

  private final ServerInstanceService service;

  @GET
  public List<ServerInstanceDto> getAllServers() {
    return service.list();
  }

  @GET
  @Path("/{serverName}")
  public ServerInstanceDto getServer(@PathParam("serverName") String serverName) {
    return service.getServer(serverName);
  }

  @POST
  public Response createServer(ServerInstanceDto serverDto, @Context UriInfo uriInfo) {
    ServerInstanceDto created = service.save(serverDto);

    URI uri = uriInfo.getAbsolutePathBuilder() //
        .path(created.getName()) //
        .build();

    return Response.created(uri).entity(created).build();
  }

  @PUT
  public ServerInstanceDto updateServer(ServerInstanceDto serverDto) {
    return service.update(serverDto);
  }

  @DELETE
  @Path("/{serverName}")
  public Response deleteServer(@PathParam("serverName") String serverName) {
    service.delete(serverName);
    return Response.noContent().build();
  }
  
  @GET
  @Path("/{serverName}/info")
  public ServerInstanceInfoDto getServerInfo(@PathParam("serverName") String serverName) {
      return service.getServerInfo(serverName);
  }

  @POST
  @Path("/{serverName}/start")
  public Response startServer(@PathParam("serverName") String serverName) {
    service.startServer(serverName);
    return Response.noContent().build();
  }

  @POST
  @Path("/{serverName}/stop")
  public Response stopServer(@PathParam("serverName") String serverName) {
    service.stopServer(serverName);
    return Response.noContent().build();
  }

  @POST
  @Path("/{serverName}/terminate")
  public Response terminateServer(@PathParam("serverName") String serverName) {
    service.terminateServer(serverName);
    return Response.noContent().build();
  }
}
