package com.webhook.core.repository;

import com.webhook.core.entity.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<Outbox, Long> {
    
    @Query(value = """
            SELECT * FROM outbox 
            WHERE published = false 
            ORDER BY created_at ASC 
            LIMIT :batchSize 
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List findUnpublishedWithLock(@Param("batchSize") int batchSize);
}