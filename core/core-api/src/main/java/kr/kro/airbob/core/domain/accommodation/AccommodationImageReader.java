package kr.kro.airbob.core.domain.accommodation;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.core.domain.image.ImageNotFoundException;
import lombok.RequiredArgsConstructor;

/** 숙소 이미지 조회 구현 컴포넌트. */
@Component
@RequiredArgsConstructor
public class AccommodationImageReader {

	private final AccommodationImageRepository accommodationImageRepository;

	/** 이미지. 없으면 ImageNotFoundException. 어느 숙소의 이미지인지는 호출하는 쪽이 확인한다. */
	public AccommodationImage readById(Long imageId) {
		return accommodationImageRepository.findById(imageId)
			.orElseThrow(ImageNotFoundException::new);
	}
}
