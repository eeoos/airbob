package kr.kro.airbob.core.support.messaging.outbox.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {}
