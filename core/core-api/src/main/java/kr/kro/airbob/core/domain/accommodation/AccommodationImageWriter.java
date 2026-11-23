package kr.kro.airbob.core.domain.accommodation;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.core.domain.image.StoredImage;
import lombok.RequiredArgsConstructor;

/** 숙소 이미지 변경 구현 컴포넌트. 저장소(S3) 업로드·삭제와 그 정리 이벤트는 호출하는 쪽이 맡는다. */
@Component
@RequiredArgsConstructor
public class AccommodationImageWriter {

	private final AccommodationImageRepository accommodationImageRepository;

	/** 업로드한 이미지 주소를 숙소에 붙이고, 저장된 이미지를 주소 순서대로 돌려준다. */
	public List<StoredImage> addAll(Accommodation accommodation, List<String> imageUrls) {
		List<AccommodationImage> images = imageUrls.stream()
			.<AccommodationImage>map(imageUrl -> AccommodationImage.builder()
				.accommodation(accommodation)
				.imageUrl(imageUrl)
				.build())
			.toList();
		return accommodationImageRepository.saveAll(images).stream()
			.map(StoredImage::from)
			.toList();
	}

	public void delete(AccommodationImage image) {
		accommodationImageRepository.delete(image);
	}

	/** 남은 이미지 중 ID가 가장 작은 것을 썸네일로 맞춘다(없으면 null). 썸네일이 바뀌었으면 true. */
	public boolean refreshThumbnail(Accommodation accommodation) {
		List<AccommodationImage> remainingImages =
			accommodationImageRepository.findByAccommodationIdOrderByIdAsc(accommodation.getId());
		String newThumbnailUrl = remainingImages.isEmpty() ? null : remainingImages.getFirst().getImageUrl();

		if (!Objects.equals(accommodation.getThumbnailUrl(), newThumbnailUrl)) {
			accommodation.updateThumbnailUrl(newThumbnailUrl);
			return true;
		}
		return false;
	}
}
