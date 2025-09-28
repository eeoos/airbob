package kr.kro.airbob.search.service;

import static kr.kro.airbob.search.event.AccommodationIndexingEvents.*;

import java.util.UUID;

import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import kr.kro.airbob.dlq.service.DeadLetterQueueService;
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

	@Transactional(readOnly = true)
	public void indexNewAccommodation(AccommodationCreatedEvent event) {
		AccommodationDocument document = documentBuilder.buildAccommodationDocument(event.accommodationUid());
		searchRepository.save(document);
		log.info("[ES-INDEX] 숙소 생성: {}", event.accommodationUid());
	}

	@Transactional(readOnly = true)
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
