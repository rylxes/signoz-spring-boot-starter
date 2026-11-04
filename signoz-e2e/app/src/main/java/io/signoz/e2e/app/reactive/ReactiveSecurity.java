package io.signoz.e2e.app.reactive;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;

/** Everything is open except {@code /e2e/secure/**}, which takes HTTP Basic as alice. */
@Configuration
public class ReactiveSecurity {

    @Bean
    SecurityWebFilterChain e2eSecurity(ServerHttpSecurity http) {
        return http.authorizeExchange(ex -> ex.pathMatchers("/e2e/secure/**").authenticated().anyExchange().permitAll())
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .build();
    }

    @Bean
    MapReactiveUserDetailsService users() {
        return new MapReactiveUserDetailsService(
                User.withUsername("alice").password("{noop}alice-pass").roles("USER", "AUDITOR").build());
    }
}
