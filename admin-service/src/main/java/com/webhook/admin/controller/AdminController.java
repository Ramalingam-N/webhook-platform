package com.webhook.admin.controller;

import com.webhook.admin.service.AdminService;
import com.webhook.core.entity.DeadLetter;
import com.webhook.core.entity.Endpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    public record EndpointRequest(String tenantId, String url, String secret) {}

    @PostMapping("/endpoints")
    public ResponseEntity<Endpoint> createEndpoint(@RequestBody EndpointRequest request) {
        Endpoint endpoint = adminService.registerEndpoint(
                request.tenantId(), request.url());
        return ResponseEntity.ok(endpoint);
    }

    @GetMapping("/dlq")
    public ResponseEntity<List<DeadLetter>> getAllDeadLetters() {
        return ResponseEntity.ok(adminService.getDeadLetters());
    }

    @PostMapping("/dlq/{id}/replay")
    public ResponseEntity<Map<String, String>> replayDlq(@PathVariable UUID id) {
        adminService.replayDeadLetter(id);
        return ResponseEntity.accepted().body(Map.of("status", "Replay initiated"));
    }
}