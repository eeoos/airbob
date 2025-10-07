package kr.kro.airbob.outbox.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.outbox.entity.Outbox;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {
}
