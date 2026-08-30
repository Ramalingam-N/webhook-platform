package com.webhook.admin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final String adminSecret;
    private final String demoSecret;

    public ApiKeyAuthFilter(String adminSecret, String demoSecret) {
        this.adminSecret = adminSecret;
        this.demoSecret = demoSecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain filterChain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-Admin-Api-Key");

        if (apiKey != null) {
            if (apiKey.equals(adminSecret)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } 
            else if (apiKey.equals(demoSecret)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        "demo_user", null, List.of(new SimpleGrantedAuthority("ROLE_VIEWER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        filterChain.doFilter(request, response);
    }
}