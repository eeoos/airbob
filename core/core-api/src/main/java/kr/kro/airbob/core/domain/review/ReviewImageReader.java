package kr.kro.airbob.core.domain.review;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.image.StoredImage;
import kr.kro.airbob.core.domain.review.repository.ReviewImageRepository;
import lombok.RequiredArgsConstructor;

/** 리뷰 이미지 조회 구현 컴포넌트. */
@Component
@RequiredArgsConstructor
public class ReviewImageReader {

	private final ReviewImageRepository reviewImageRepository;

	/** 회원이 쓴 리뷰의 이미지. 없거나 다른 회원의 이미지면 ReviewAccessDeniedException. */
	public ReviewImage readByAuthor(Long imageId, Long memberId) {
		return reviewImageRepository.findByIdAndReviewAuthorId(imageId, memberId)
			.orElseThrow(ReviewAccessDeniedException::new);
	}

	/** 리뷰 ID별 이미지. 이미지가 없는 리뷰는 맵에 없다. */
	public Map<Long, List<StoredImage>> readByReviewIds(List<Long> reviewIds) {
		return reviewImageRepository.findAllByReview_IdIn(reviewIds).stream()
			.collect(Collectors.groupingBy(
				reviewImage -> reviewImage.getReview().getId(),
				Collectors.mapping(StoredImage::from, Collectors.toList())));
	}
}
