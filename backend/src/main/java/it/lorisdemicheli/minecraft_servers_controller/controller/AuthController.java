package it.lorisdemicheli.minecraft_servers_controller.controller;

import it.lorisdemicheli.minecraft_servers_controller.annotation.Api;
import it.lorisdemicheli.minecraft_servers_controller.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Api
@RestController
@RequestMapping("/auth") 
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.username(),
                        request.password()
                )
        );

        String token = jwtService.generateToken(authentication.getName());
        
        return ResponseEntity.ok(new AuthResponse(token));
    }

     public record LoginRequest(String username, String password) {}
    public record AuthResponse(String token) {}
}