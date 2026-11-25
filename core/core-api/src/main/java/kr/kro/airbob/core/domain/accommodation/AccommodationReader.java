package kr.kro.airbob.core.domain.accommodation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
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

	/** 호스트의 삭제되지 않은 숙소를 쓰기 잠금으로 읽는다. 없거나 다른 호스트의 숙소면 AccommodationNotFoundException. */
	public Accommodation readOwnedForUpdate(Long accommodationId, Long hostId) {
		return accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(
			accommodationId, hostId, AccommodationStatus.DELETED).orElseThrow(AccommodationNotFoundException::new);
	}

	/** 호스트의 삭제되지 않은 숙소. 잠그지 않는다. 없거나 다른 호스트의 숙소면 AccommodationNotFoundException. */
	public Accommodation readOwned(Long accommodationId, Long hostId) {
		return accommodationRepository.findByIdAndMemberIdAndStatusNot(
			accommodationId, hostId, AccommodationStatus.DELETED).orElseThrow(AccommodationNotFoundException::new);
	}

	/** 게시 숙소의 시간대. 게시 숙소가 아니면 AccommodationNotFoundException. */
	public String readPublishedTimeZoneId(Long accommodationId) {
		return accommodationRepository
			.findBookingProjectionByIdAndStatus(accommodationId, AccommodationStatus.PUBLISHED)
			.orElseThrow(AccommodationNotFoundException::new)
			.timeZoneId();
	}

	/** 호스트 숙소 목록을 커서 다음부터 한 페이지 읽는다. status가 null이면 상태로 거르지 않는다. */
	public Slice<HostAccommodationProjection> readHostPage(
		Long hostId, CursorRequest.CursorPageRequest cursorRequest, AccommodationStatus status) {
		return accommodationRepository.findMyAccommodationsByHostIdWithCursor(
			hostId,
			cursorRequest.lastId(),
			cursorRequest.lastCreatedAt(),
			status,
			PageRequest.of(0, cursorRequest.size()));
	}

	/** 호스트의 숙소 상세. 없거나 다른 호스트의 숙소면 AccommodationNotFoundException. */
	public HostAccommodationDetailProjection readHostDetail(Long accommodationId, Long hostId) {
		return accommodationRepository.findWithDetailsByIdAndHostId(accommodationId, hostId)
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
