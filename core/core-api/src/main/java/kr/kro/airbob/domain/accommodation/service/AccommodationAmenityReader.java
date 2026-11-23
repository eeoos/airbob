package kr.kro.airbob.domain.accommodation.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import lombok.RequiredArgsConstructor;

/** 숙소 편의시설 조회 구현 컴포넌트. 다른 도메인은 AccommodationAmenityRepository 대신 이 컴포넌트로 읽는다. */
@Component
@RequiredArgsConstructor
public class AccommodationAmenityReader {

	private final AccommodationAmenityRepository amenityRepository;

	public List<AccommodationAmenity> findAllByAccommodationUid(UUID accommodationUid) {
		return amenityRepository.findAllByAccommodation_AccommodationUid(accommodationUid);
	}
}
