package it.lorisdemicheli.server_controller.resource;

import org.jboss.resteasy.reactive.RestStreamElementType;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/servers/{serverName}/console")
public class ServerConsoleResource {

  @Inject
  KubernetesClient k8sClient;
  
  @GET
  @Path("/logs")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  public Multi<String> logs(@PathParam("serverName") String serverName) {
    return null;
//    return Multi.createFrom().emitter(emitter -> {
//      try {
//          // 1. Controlliamo se il pod esiste ed è Running
//          var podResource = k8sClient.pods().withName(podName);
//          if (podResource.get() == null || !"Running".equals(podResource.get().getStatus().getPhase())) {
//              emitter.emit("SYSTEM: Il server Minecraft è spento o non ancora pronto.");
//              emitter.complete();
//              return;
//          }
//
//          // 2. Apriamo lo stream verso Kubernetes (equivalente di kubectl logs -f)
//          // 'withPrettyOutput' è opzionale
//          LogWatch watch = podResource.watchLog();
//          
//          // 3. Leggiamo lo stream riga per riga
//          BufferedReader reader = new BufferedReader(new InputStreamReader(watch.getOutput()));
//          
//          String line;
//          while ((line = reader.readLine()) != null) {
//              // 4. Inviamo la riga al client (browser)
//              if (emitter.isCancelled()) {
//                  break; // Il client ha chiuso la pagina
//              }
//              emitter.emit(line);
//          }
//          
//          // 5. Se il loop finisce (il pod muore o chiude lo stream)
//          emitter.complete();
//          watch.close();
//
//      } catch (Exception e) {
//          if (!emitter.isCancelled()) {
//              emitter.fail(e);
//          }
//      }
//  }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()); 
//  // ^^^ IMPORTANTE: Spostiamo il lavoro su un thread pool per non bloccare l'Event Loop di Quarkus
  }

  @POST
  @Path("/commands")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  public Response commands(@PathParam("serverName") String serverName, String command) {
    return Response.accepted().build();
  }
}
