package kr.kro.airbob.domain.event.repository;

import kr.kro.airbob.domain.event.entity.EventParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventParticipantRepository extends JpaRepository<EventParticipant, Long> {
    long countByEventId(Long eventId);
}
