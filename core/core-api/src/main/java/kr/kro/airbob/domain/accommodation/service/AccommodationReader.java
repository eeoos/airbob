package kr.kro.airbob.domain.accommodation.service;

import org.springframework.stereotype.Component;

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
}
