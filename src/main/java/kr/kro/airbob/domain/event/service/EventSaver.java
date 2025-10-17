package kr.kro.airbob.domain.event.service;

import java.time.LocalDateTime;
import kr.kro.airbob.domain.event.entity.Event;
import kr.kro.airbob.domain.event.entity.EventParticipant;
import kr.kro.airbob.domain.event.repository.EventParticipantRepository;
import kr.kro.airbob.domain.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class EventSaver {

    private final EventParticipantRepository eventParticipantRepository;
    private final EventRepository eventRepository;

    @Transactional
    public void saveToDatabase(Long eventId, Long memberId) {
        Event event = eventRepository.findByIdWithLock(eventId)
                .orElseThrow(() -> new IllegalArgumentException("이벤트가 존재하지 않습니다."));

        if (event.getEndAt().isBefore(LocalDateTime.now())){
            throw new IllegalStateException("이벤트 마감됨");
        }

        long count = eventParticipantRepository.countByEventId(eventId);
        if (count >= event.getMaxParticipants()) {
            throw new IllegalStateException("이벤트 마감됨");
        }

        EventParticipant eventParticipant = EventParticipant.builder()
                .event(event)
                .memberId(memberId)
                .build();

        eventParticipantRepository.save(eventParticipant);
    }

}
