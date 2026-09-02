package com.webhook.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.admin.config.SecurityConfig;
import com.webhook.admin.exception.GlobalExceptionHandler;
import com.webhook.admin.service.AdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "admin.api.secret=test-admin-key",
        "admin.api.demo-secret=test-demo-key"
})
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AdminService adminService;

    @Test
    @DisplayName("I4: POST /endpoints with admin key -> 200 OK")
    void postEndpointsWithAdminKey() throws Exception {
        AdminController.EndpointRequest request = new AdminController.EndpointRequest("tenant-1", "https://api.com", null);

        mockMvc.perform(post("/v1/admin/endpoints")
                        .header("X-Admin-Api-Key", "test-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("I5: GET /dlq with demo key -> 200 OK (Viewer allowed to read)")
    void getDlqWithDemoKey() throws Exception {
        mockMvc.perform(get("/v1/admin/dlq")
                        .header("X-Admin-Api-Key", "test-demo-key"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("I6: POST /dlq/{id}/replay with demo key -> 403 Forbidden")
    void postReplayWithDemoKeyForbidden() throws Exception {
        mockMvc.perform(post("/v1/admin/dlq/" + UUID.randomUUID() + "/replay")
                        .header("X-Admin-Api-Key", "test-demo-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("I7: Missing API key -> 403 Forbidden")
    void missingKeyForbidden() throws Exception {
        mockMvc.perform(get("/v1/admin/dlq"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("I8: Replay already-replayed -> 409 Conflict")
    void replayAlreadyReplayedReturns409() throws Exception {
        UUID dlqId = UUID.randomUUID();
        
        doThrow(new IllegalStateException("already been replayed"))
                .when(adminService).replayDeadLetter(any(UUID.class));

        mockMvc.perform(post("/v1/admin/dlq/" + dlqId + "/replay")
                        .header("X-Admin-Api-Key", "test-admin-key"))
                .andExpect(status().isConflict());
    }
}