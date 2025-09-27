package kr.kro.airbob.dlq.reprocessor;

import static kr.kro.airbob.search.event.AccommodationIndexingEvents.*;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;
import kr.kro.airbob.search.service.AccommodationDocumentBuilder;
import kr.kro.airbob.search.service.AccommodationIndexUpdater;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class AccommodationEventReprocessor {

	private final AccommodationSearchRepository searchRepository;
	private final AccommodationDocumentBuilder documentBuilder;
	private final AccommodationIndexUpdater indexUpdater;
	private final ObjectMapper objectMapper;

	public boolean reprocess(String eventType, String eventData) {
		try {
			switch (eventType) {
				case "AccommodationCreatedEvent":
					return handleAccommodationCreated(eventData);
				case "AccommodationUpdatedEvent":
					return handleAccommodationUpdated(eventData);
				case "AccommodationDeletedEvent":
					return handleAccommodationDeleted(eventData);
				case "ReviewSummaryChangedEvent":
					return handleReviewSummaryChanged(eventData);
				case "ReservationChangedEvent":
					return handleReservationChanged(eventData);
				default:
					log.warn("지원하지 않는 이벤트 타입: {}", eventType);
					return false;
			}
		} catch (Exception e) {
			log.error("이벤트 재처리 실패: eventType={}, error={}", eventType, e.getMessage(), e);
			return false;
		}
	}

	private boolean handleAccommodationCreated(String eventData) throws Exception {
		AccommodationCreatedEvent event = objectMapper.readValue(eventData, AccommodationCreatedEvent.class);

		AccommodationDocument document = documentBuilder.buildAccommodationDocument(event.accommodationUid());
		searchRepository.save(document);

		return true;
	}

	private boolean handleAccommodationUpdated(String eventData) throws Exception {
		AccommodationUpdatedEvent event = objectMapper.readValue(eventData, AccommodationUpdatedEvent.class);

		AccommodationDocument document = documentBuilder.buildAccommodationDocument(event.accommodationUid());
		searchRepository.save(document);

		return true;
	}

	private boolean handleAccommodationDeleted(String eventData) throws Exception {
		AccommodationDeletedEvent event = objectMapper.readValue(eventData, AccommodationDeletedEvent.class);

		searchRepository.deleteById(UUID.fromString(event.accommodationUid()));

		return true;
	}

	private boolean handleReviewSummaryChanged(String eventData) throws Exception {
		ReviewSummaryChangedEvent event = objectMapper.readValue(eventData, ReviewSummaryChangedEvent.class);

		indexUpdater.updateReviewSummaryInIndex(event.accommodationUid());

		return true;
	}

	private boolean handleReservationChanged(String eventData) throws Exception {
		ReservationChangedEvent event = objectMapper.readValue(eventData, ReservationChangedEvent.class);

		indexUpdater.updateReservedDatesInIndex(event.accommodationUid());

		return true;
	}
}
