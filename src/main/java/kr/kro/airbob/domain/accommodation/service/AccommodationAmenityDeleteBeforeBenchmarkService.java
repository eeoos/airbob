package kr.kro.airbob.domain.accommodation.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;

@Service
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class AccommodationAmenityDeleteBeforeBenchmarkService {

	private final AccommodationAmenityRepository accommodationAmenityRepository;
	private final AccommodationCommandService accommodationCommandService;

	public AccommodationAmenityDeleteBeforeBenchmarkService(
		AccommodationAmenityRepository accommodationAmenityRepository,
		AccommodationCommandService accommodationCommandService
	) {
		this.accommodationAmenityRepository = accommodationAmenityRepository;
		this.accommodationCommandService = accommodationCommandService;
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
		accommodationCommandService.updateAccommodationWithAmenityDeletion(
			accommodationId, request, memberId, accommodationAmenityRepository::deleteByAccommodationId);
	}
}
