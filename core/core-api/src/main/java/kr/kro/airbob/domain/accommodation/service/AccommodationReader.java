package kr.kro.airbob.domain.accommodation.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.accommodation.InventorySeedTarget;
import kr.kro.airbob.domain.accommodation.RecentlyViewedAccommodationProjection;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import lombok.RequiredArgsConstructor;

/**
 * Implement component through which other domains read accommodations, instead of using
 * AccommodationRepository.
 */
@Component
@RequiredArgsConstructor
public class AccommodationReader {

	private final AccommodationRepository accommodationRepository;

	public Accommodation readPublished(Long accommodationId) {
		return accommodationRepository.findByIdAndStatus(accommodationId, AccommodationStatus.PUBLISHED)
			.orElseThrow(AccommodationNotFoundException::new);
	}

	/** 예약 견적용 숙소와 숙박 인원 정책. 잠그지 않는다. */
	public Optional<Accommodation> findQuoteSnapshotByIdAndStatus(Long accommodationId, AccommodationStatus status) {
		return accommodationRepository.findQuoteSnapshotByIdAndStatus(accommodationId, status);
	}

	/**
	 * 체크아웃용 숙소와 숙박 인원 정책을 공유 잠금으로 바로(FOR SHARE NOWAIT) 읽는다.
	 * 잠금 실패를 포함한 Repository 예외는 바꾸지 않고 그대로 던진다.
	 */
	public Optional<Accommodation> findBookingSnapshotForShare(Long accommodationId, AccommodationStatus status) {
		return accommodationRepository.findBookingSnapshotForShare(accommodationId, status);
	}

	/** 재고 seed 대상. 상태가 같은 숙소를 ID 오름차순으로 커서 다음부터 읽는다. */
	public List<InventorySeedTarget> findInventorySeedTargets(
		AccommodationStatus status, long afterAccommodationId, Pageable pageable) {
		return accommodationRepository.findInventorySeedTargets(status, afterAccommodationId, pageable);
	}

	/** 검색 문서용 숙소와 상세 연관. */
	public Optional<Accommodation> findWithDetailsByAccommodationUid(UUID accommodationUid) {
		return accommodationRepository.findWithDetailsByAccommodationUid(accommodationUid);
	}

	/** 최근 본 숙소 목록용 주소·리뷰 요약. 상태가 다르거나 없는 숙소는 빠진다. */
	public List<RecentlyViewedAccommodationProjection> findWithAddressAndReviewSummaryByIdInAndStatus(
		List<Long> accommodationIds, AccommodationStatus status) {
		return accommodationRepository.findWithAddressAndReviewSummaryByIdInAndStatus(accommodationIds, status);
	}
}
