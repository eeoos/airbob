package kr.kro.airbob.domain.accommodation.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import lombok.RequiredArgsConstructor;

@Service
@Profile("read-model-benchmark")
@RequiredArgsConstructor
public class AccommodationDetailBenchmarkService {

	private final AccommodationDetailReader detailReader;

	@Transactional(readOnly = true)
	public AccommodationResponse.DetailInfo findAccommodationBefore(Long accommodationId, Long viewerId) {
		AccommodationDetailSnapshot snapshot = detailReader.load(accommodationId);
		boolean isInWishlist = viewerId != null && detailReader.isInWishlist(accommodationId, viewerId);

		return AccommodationResponse.DetailInfo.from(snapshot, isInWishlist);
	}
}
