package com.webhook.core.repository;

import com.webhook.core.entity.Event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {
    // Note: I added  to the Optional return type so it returns the actual entity
    Optional findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);
}