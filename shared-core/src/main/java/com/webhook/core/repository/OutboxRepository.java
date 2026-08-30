package com.webhook.core.repository;

import com.webhook.core.entity.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<Outbox, Long> {
    
@Query(value = """
        SELECT * FROM outbox 
        WHERE published = false 
        AND created_at < NOW() - INTERVAL '1 minute' 
        ORDER BY created_at ASC 
        LIMIT :batchSize 
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Outbox> findUnpublishedWithLock(@Param("batchSize") int batchSize);
    
    @Modifying
    @Query("UPDATE Outbox o SET o.published = true WHERE o.id = :outboxId")
    int markAsPublished(@Param("outboxId") Long outboxId);

    @Modifying
    @Query("UPDATE Outbox o SET o.published = false WHERE o.id = :outboxId")
    int markAsUnpublished(@Param("outboxId") Long outboxId);
}