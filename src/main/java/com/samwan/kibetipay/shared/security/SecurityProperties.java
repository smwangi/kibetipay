package com.samwan.kibetipay.shared.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Demo users for local development (phase 4 replaces this with an OAuth2/JWT issuer).
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(@Valid List<User> users) {

    public SecurityProperties {
        users = users == null ? List.of() : List.copyOf(users);
    }

    public record User(@NotBlank String username, @NotBlank String password, List<String> roles) {
        public User {
            roles = roles == null || roles.isEmpty() ? List.of("USER") : List.copyOf(roles);
        }

        @Override
        public String toString() {
            return "User[" + username + ", roles=" + roles + "]";   // never print the password
        }
    }
}
