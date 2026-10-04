package com.aftercare.identity;

import java.time.Instant;
import java.util.UUID;

import com.aftercare.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final CurrentActorService currentActorService;

    public AuthController(AuthService authService, CurrentActorService currentActorService) {
        this.authService = authService;
        this.currentActorService = currentActorService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginData> login(@Valid @RequestBody LoginRequest request) {
        var result = authService.login(request.merchantCode(), request.username(), request.password());
        var actor = result.actor();
        return ApiResponse.ok(new LoginData(result.accessToken(), "Bearer", result.expiresAt(),
                actor.tenantId(), actor.actorId(), actor.role()));
    }

    @GetMapping("/me")
    public ApiResponse<ActorData> me() {
        var actor = currentActorService.currentActor();
        return ApiResponse.ok(new ActorData(actor.tenantId(), actor.actorId(), actor.role()));
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String merchantCode,
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password) {
    }

    public record LoginData(String accessToken, String tokenType, Instant expiresAt,
            UUID tenantId, UUID actorId, Role role) {
    }

    public record ActorData(UUID tenantId, UUID actorId, Role role) {
    }
}
