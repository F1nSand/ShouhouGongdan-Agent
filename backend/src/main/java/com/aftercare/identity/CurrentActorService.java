package com.aftercare.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentActorService {
    public ActorContext currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof ActorContext actor)) {
            throw new IllegalStateException("An authenticated actor is required");
        }
        return actor;
    }
}
