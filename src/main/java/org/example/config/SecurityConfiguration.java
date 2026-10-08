package org.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                .requestMatchers("/actuator/health", "/actuator/health/**",
                        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs",
                        "/v3/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/foods", "/api/foods/**").permitAll()
                // For development/testing purpose without full auth module, permit all or configure a mock JWT filter.
                // We permit all here for testing with Postman as requested since Auth module isn't implemented.
                .requestMatchers(HttpMethod.POST, "/api/foods").permitAll()
                .requestMatchers(HttpMethod.PUT, "/api/foods/**").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/api/foods/**").permitAll()
                .requestMatchers("/api/cart", "/api/cart/**").permitAll()
                .anyRequest().denyAll()).build();
    }
}
