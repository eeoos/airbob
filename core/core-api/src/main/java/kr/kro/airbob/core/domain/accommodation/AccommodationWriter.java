package kr.kro.airbob.core.domain.accommodation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.core.domain.accommodation.AccommodationLocationResolver.ResolvedLocation;
import kr.kro.airbob.core.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.core.domain.accommodation.repository.AccommodationHistoryRepository;
import kr.kro.airbob.core.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.core.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.core.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.core.domain.member.Member;
import lombok.RequiredArgsConstructor;

/** 숙소 변경 구현 컴포넌트. 상태 변경마다 숙소 이력(SCD2)을 남긴다: 직전 현재 행을 닫고 새 스냅샷을 연다. */
@Component
@RequiredArgsConstructor
public class AccommodationWriter {

	private final AccommodationRepository accommodationRepository;
	private final AccommodationHistoryRepository accommodationHistoryRepository;
	private final AddressRepository addressRepository;
	private final OccupancyPolicyRepository occupancyPolicyRepository;
	private final AccommodationAmenityRepository accommodationAmenityRepository;
	private final Clock clock;

	/** 호스트의 빈 숙소를 만들고 생성 이력을 남긴다. */
	public Accommodation create(Member host) {
		Accommodation accommodation = Accommodation.createAccommodation(host);
		Accommodation savedAccommodation = accommodationRepository.save(accommodation);

		recordHistory(savedAccommodation, ChangeType.CREATE, "숙소 생성");
		return savedAccommodation;
	}

	/** 새 주소를 저장하고 숙소의 주소와 시간대를 바꾼다. 위치가 그대로면(null) 아무것도 하지 않는다. */
	public void updateLocation(Accommodation accommodation, ResolvedLocation resolvedLocation) {
		if (resolvedLocation == null) {
			return;
		}

		Address savedAddress = addressRepository.save(resolvedLocation.address());
		accommodation.updateLocation(savedAddress, resolvedLocation.timeZone());
	}

	/** 인원 정책을 교체한다. 정책이 없던 숙소면 새로 저장해 연결한다. null이면 유지한다. */
	public void updateOccupancyPolicy(Accommodation accommodation, NewOccupancyPolicy occupancyPolicy) {
		if (occupancyPolicy == null) {
			return;
		}

		OccupancyPolicy currentPolicy = accommodation.getOccupancyPolicy();
		if (currentPolicy == null) {
			OccupancyPolicy newPolicy = OccupancyPolicy.createOccupancyPolicy(occupancyPolicy);
			OccupancyPolicy savedPolicy = occupancyPolicyRepository.save(newPolicy);
			accommodation.updateOccupancyPolicy(savedPolicy);
		} else {
			currentPolicy.update(occupancyPolicy);
		}
	}

	/** 편의시설을 코드별 개수로 전체 교체한다. null이면 유지하고, 빈 맵이면 모두 지운다. */
	public void replaceAmenities(Accommodation accommodation, Map<String, Integer> amenityCountMap) {
		if (amenityCountMap == null) {
			return;
		}

		accommodationAmenityRepository.deleteByAccommodationIdInBulk(accommodation.getId());

		if (!amenityCountMap.isEmpty()) {
			List<AccommodationAmenity> accommodationAmenityList = amenityCountMap.entrySet().stream()
				.map(entry -> AccommodationAmenity.createAccommodationAmenity(
					accommodation, entry.getKey(), entry.getValue()))
				.toList();

			accommodationAmenityRepository.saveAll(accommodationAmenityList);
		}
	}

	public void delete(Accommodation accommodation) {
		accommodation.delete();
		recordHistory(accommodation, ChangeType.DELETE, "숙소 삭제");
	}

	public void publish(Accommodation accommodation) {
		accommodation.publish();
		recordHistory(accommodation, ChangeType.STATUS_CHANGE, "숙소 게시");
	}

	public void unpublish(Accommodation accommodation) {
		accommodation.unpublish();
		recordHistory(accommodation, ChangeType.STATUS_CHANGE, "숙소 게시 중단");
	}

	public void recordHistory(Accommodation accommodation, ChangeType changeType, String reason) {
		LocalDateTime changedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		accommodationHistoryRepository.findByAccommodationIdAndValidTo(
			accommodation.getId(), HistoryConstants.FOREVER
		).ifPresent(current -> current.close(changedAt));
		accommodationHistoryRepository.save(
			AccommodationHistory.of(accommodation, changeType, reason, changedAt));
	}
}
