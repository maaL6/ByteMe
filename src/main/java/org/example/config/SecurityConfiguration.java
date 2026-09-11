package org.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/actuator/health", "/actuator/health/**",
                        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs",
                        "/v3/api-docs/**").permitAll()
                .anyRequest().denyAll()).build();
    }
}
