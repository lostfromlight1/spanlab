package com.spanlab.order;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

// Spring Data repository for orders. The interface is the whole class.
interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
}

// Pending outbox rows. SKIP LOCKED lets a second publisher take the next
// rows instead of waiting. This lab runs one publisher. The schema name
// "order" is quoted because it is a SQL keyword.
interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = """
            SELECT * FROM "order".outbox_event
            WHERE status = 'NEW'
            ORDER BY created_at
            LIMIT 20
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPending();
}
