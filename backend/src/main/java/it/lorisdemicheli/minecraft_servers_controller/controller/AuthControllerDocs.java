package it.lorisdemicheli.minecraft_servers_controller.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

@RestController
public class AuthControllerDocs {

    @Operation(
        summary = "Effettua il login", 
        description = "Inserisci le credenziali qui. Il browser salverà automaticamente il JSESSIONID."
    )
    @PostMapping(value = "/api/auth/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void login(
            @Parameter(description = "Username") @RequestParam("username") String username,
            @Parameter(description = "Password") @RequestParam("password") String password) {
        
        throw new IllegalStateException("Questo metodo non dovrebbe essere chiamato direttamente.");
    }
}