package com.webhook.core.repository;

import com.webhook.core.entity.DeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeadLetterRepository extends JpaRepository<DeadLetter, UUID> {
    
    Optional<DeadLetter> findByEventId(UUID eventId);

    @Modifying
    @Query("UPDATE DeadLetter d SET d.replayed = true WHERE d.id = :id AND d.replayed = false")
    int markAsReplayedAtomically(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE DeadLetter d SET d.replayed = false WHERE d.id = :id")
    void resetReplayedFlag(@Param("id") UUID id);
}