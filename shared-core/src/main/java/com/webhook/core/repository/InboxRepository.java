package com.webhook.core.repository;

import com.webhook.core.entity.Inbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface InboxRepository extends JpaRepository<Inbox, Long> {
    
    @Modifying
    @Query(value = """
        INSERT INTO inbox (event_id, endpoint_id, processed_at)
        VALUES (:eventId, :endpointId, now())
        ON CONFLICT (event_id, endpoint_id) DO NOTHING
        """, nativeQuery = true)
    int claim(@Param("eventId") UUID eventId, @Param("endpointId") UUID endpointId);

    @Modifying
    @Query(value = "DELETE FROM inbox WHERE event_id = :eventId AND endpoint_id = :endpointId", nativeQuery = true)
    void release(@Param("eventId") UUID eventId, @Param("endpointId") UUID endpointId);
}