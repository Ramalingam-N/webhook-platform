package com.webhook.admin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(initializers = ConfigDataApplicationContextInitializer.class)
@ActiveProfiles("local")
class ApiKeyAuthFilterTest {

    private ApiKeyAuthFilter filter;

    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;

    @Value("${admin.api.secret}")
    private String ADMIN_SECRET;
    @Value("${admin.api.demo-secret}")
    private String DEMO_SECRET;

    private final String ADMIN_VALUE = "123456";
    private final String DEMO_VALUE = "67890";

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(ADMIN_SECRET, DEMO_SECRET);
        
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
        
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("U1: Admin key sets ROLE_ADMIN")
    void adminKeySetsRoleAdmin() throws ServletException, IOException {
        when(request.getHeader("X-Admin-Api-Key")).thenReturn(ADMIN_VALUE);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("admin");
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("U2: Demo key sets ROLE_VIEWER")
    void demoKeySetsRoleViewer() throws ServletException, IOException {
        when(request.getHeader("X-Admin-Api-Key")).thenReturn(DEMO_VALUE);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("demo_user");
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_VIEWER");

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("U3: Wrong key leaves authentication null")
    void wrongKeyLeavesAuthNull() throws ServletException, IOException {
        when(request.getHeader("X-Admin-Api-Key")).thenReturn("invalid-key-hacker");

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNull();

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("U4: Missing key (null) leaves authentication null")
    void missingKeyLeavesAuthNull() throws ServletException, IOException {
        when(request.getHeader("X-Admin-Api-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNull();

        verify(filterChain).doFilter(request, response);
    }
}