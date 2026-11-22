package kr.kro.airbob.core.domain.review;

import java.util.List;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.image.StoredImage;
import kr.kro.airbob.core.domain.review.repository.ReviewImageRepository;
import lombok.RequiredArgsConstructor;

/** 리뷰 이미지 변경 구현 컴포넌트. 저장소(S3) 업로드와 삭제는 호출하는 쪽이 맡는다. */
@Component
@RequiredArgsConstructor
public class ReviewImageWriter {

	private final ReviewImageRepository reviewImageRepository;

	/** 업로드한 이미지 주소를 리뷰에 붙이고, 저장된 이미지를 주소 순서대로 돌려준다. */
	public List<StoredImage> addAll(Review review, List<String> imageUrls) {
		List<ReviewImage> images = imageUrls.stream()
			.<ReviewImage>map(imageUrl -> ReviewImage.builder()
				.review(review)
				.imageUrl(imageUrl)
				.build())
			.toList();
		return reviewImageRepository.saveAll(images).stream()
			.map(StoredImage::from)
			.toList();
	}

	public void delete(ReviewImage image) {
		reviewImageRepository.delete(image);
	}
}
