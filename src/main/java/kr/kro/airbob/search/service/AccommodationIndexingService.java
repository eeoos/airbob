package kr.kro.airbob.search.service;

import static kr.kro.airbob.search.event.AccommodationIndexingEvents.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccommodationIndexingService {

	private final AccommodationSearchRepository searchRepository;
	private final AccommodationDocumentBuilder documentBuilder;
	private final AccommodationIndexUpdater indexUpdater;

	public void updateAccommodationIndex(AccommodationUpdatedEvent event) {
		AccommodationDocument document = documentBuilder.buildAccommodationDocument(event.accommodationUid());
		searchRepository.save(document);
		log.info("[ES-INDEX] 숙소 업데이트: {}", event.accommodationUid());
	}

	public void deleteAccommodationIndex(AccommodationDeletedEvent event) {
		searchRepository.deleteById(java.util.UUID.fromString(event.accommodationUid()));
		log.info("[ES-INDEX] 숙소 삭제: {}", event.accommodationUid());
	}

	public void updateReviewSummaryInIndex(ReviewSummaryChangedEvent event) {
		indexUpdater.updateReviewSummaryInIndex(event.accommodationUid());
		log.info("[ES-INDEX] 리뷰 요약 업데이트: {}", event.accommodationUid());
	}

	public void updateReservedDatesInIndex(ReservationChangedEvent event) {
		indexUpdater.updateReservedDatesInIndex(event.accommodationUid());
		log.info("[ES-INDEX] 예약 날짜 업데이트: {}", event.accommodationUid());
	}
}
