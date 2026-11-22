package kr.kro.airbob.core.domain.review;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.member.Member;
import kr.kro.airbob.core.domain.review.repository.ReviewRepository;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import lombok.RequiredArgsConstructor;

/** 리뷰 변경 구현 컴포넌트. */
@Component
@RequiredArgsConstructor
public class ReviewWriter {

	private final ReviewRepository reviewRepository;

	/** 게시 상태의 새 리뷰를 저장한다. */
	public Review create(Accommodation accommodation, Member author, int rating, String content) {
		Review review = Review.builder()
			.rating(rating)
			.content(content)
			.accommodation(accommodation)
			.author(author)
			.build();
		return reviewRepository.save(review);
	}
}
