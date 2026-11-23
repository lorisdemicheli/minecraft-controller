package it.lorisdemicheli.minecraft_servers_controller.controller;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.lorisdemicheli.minecraft_servers_controller.integration.CurseForgeClient;
import lombok.RequiredArgsConstructor;

/** Third party catalogues used by the creation wizard. */
@RestController
@Tag(name = "INTEGRATIONS")
@RequiredArgsConstructor
@RequestMapping("/integrations")
public class IntegrationController {

  private final CurseForgeClient curseForge;

  public record IntegrationStatus(boolean curseforgeEnabled) {
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<IntegrationStatus> status() {
    return ResponseEntity.ok(new IntegrationStatus(curseForge.enabled()));
  }

  @GetMapping(path = "/curseforge/modpacks", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<List<CurseForgeClient.Modpack>> searchCurseForge(
      @RequestParam String query) {
    return ResponseEntity.ok(curseForge.searchModpacks(query));
  }
}
