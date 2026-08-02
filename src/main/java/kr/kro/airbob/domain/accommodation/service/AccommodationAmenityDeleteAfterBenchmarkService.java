package kr.kro.airbob.domain.accommodation.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;

@Service
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class AccommodationAmenityDeleteAfterBenchmarkService {

	private final AccommodationAmenityRepository accommodationAmenityRepository;

	public AccommodationAmenityDeleteAfterBenchmarkService(
		AccommodationAmenityRepository accommodationAmenityRepository
	) {
		this.accommodationAmenityRepository = accommodationAmenityRepository;
	}

	@Transactional
	public int deleteByAccommodationId(long accommodationId) {
		return accommodationAmenityRepository.deleteByAccommodationIdInBulk(accommodationId);
	}
}
