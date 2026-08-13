package kr.kro.airbob.domain.accommodation.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.commoncode.common.CommonCodeGroups;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AmenityRequest;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.AccommodationHistory;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationHistoryRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;

@Service
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class AccommodationAmenityDeleteBeforeBenchmarkService {

	private final AccommodationAmenityRepository accommodationAmenityRepository;
	private final AccommodationRepository accommodationRepository;
	private final AccommodationHistoryRepository accommodationHistoryRepository;
	private final CommonCodeService commonCodeService;
	private final Clock clock;

	public AccommodationAmenityDeleteBeforeBenchmarkService(
		AccommodationAmenityRepository accommodationAmenityRepository,
		AccommodationRepository accommodationRepository,
		AccommodationHistoryRepository accommodationHistoryRepository,
		CommonCodeService commonCodeService,
		Clock clock
	) {
		this.accommodationAmenityRepository = accommodationAmenityRepository;
		this.accommodationRepository = accommodationRepository;
		this.accommodationHistoryRepository = accommodationHistoryRepository;
		this.commonCodeService = commonCodeService;
		this.clock = clock;
	}

	@Transactional
	public void deleteByAccommodationId(long accommodationId) {
		accommodationAmenityRepository.deleteByAccommodationId(accommodationId);
	}

	@Transactional
	public void fullReplacement(
		long accommodationId,
		AccommodationRequest.Update request,
		long memberId
	) {
		Accommodation accommodation = accommodationRepository
			.findByIdAndMemberIdAndStatusNot(accommodationId, memberId, AccommodationStatus.DELETED)
			.orElseThrow(AccommodationNotFoundException::new);

		accommodationAmenityRepository.deleteByAccommodationId(accommodationId);
		if (!request.amenityInfos().isEmpty()) {
			saveValidAmenities(request.amenityInfos(), accommodation);
		}
		recordHistory(accommodation);
	}

	private void saveValidAmenities(
		List<AmenityRequest.AmenityInfo> amenityInfos,
		Accommodation accommodation
	) {
		Map<String, Integer> amenityCountMap = amenityInfos.stream()
			.filter(info -> info.count() > 0)
			.map(info -> new AbstractMap.SimpleEntry<>(
				info.name() == null ? null : info.name().toUpperCase(Locale.ROOT), info.count()))
			.filter(entry -> commonCodeService.isValidCode(CommonCodeGroups.AMENITY_TYPE, entry.getKey()))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Integer::sum));
		if (amenityCountMap.isEmpty()) {
			return;
		}
		accommodationAmenityRepository.saveAll(amenityCountMap.entrySet().stream()
			.map(entry -> AccommodationAmenity.createAccommodationAmenity(
				accommodation, entry.getKey(), entry.getValue()
			))
			.toList());
	}

	private void recordHistory(Accommodation accommodation) {
		LocalDateTime changedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		accommodationHistoryRepository
			.findByAccommodationIdAndValidTo(accommodation.getId(), HistoryConstants.FOREVER)
			.ifPresent(current -> current.close(changedAt));
		accommodationHistoryRepository.save(
			AccommodationHistory.of(accommodation, ChangeType.UPDATE, "숙소 정보 수정", changedAt)
		);
	}
}
