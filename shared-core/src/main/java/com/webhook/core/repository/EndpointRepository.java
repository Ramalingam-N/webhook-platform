package com.webhook.core.repository;

import com.webhook.core.entity.Endpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {
    
    List<Endpoint> findByTenantIdAndStatus(String tenantId, String status);
}