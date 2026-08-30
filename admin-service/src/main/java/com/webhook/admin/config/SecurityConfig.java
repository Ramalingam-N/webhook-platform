package com.webhook.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Value("${admin.api.secret:secret-admin-api-key}")
    private String adminSecret;

    @Value("${admin.api.demo-secret:demo-viewer-api-key}")
    private String demoSecret;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus", "/actuator/metrics/**").permitAll()
                .requestMatchers(HttpMethod.GET,  "/v1/admin/**").hasAnyRole("ADMIN", "VIEWER")
                .requestMatchers(HttpMethod.POST, "/v1/admin/**").hasRole("ADMIN")
                .anyRequest().permitAll()
            )
            .addFilterBefore(new ApiKeyAuthFilter(adminSecret, demoSecret), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}