package kr.kro.airbob.core.domain.review;

import java.time.LocalDateTime;
import java.util.List;

import com.querydsl.core.annotations.QueryProjection;

import kr.kro.airbob.core.domain.image.StoredImage;

/** 숙소 리뷰 목록의 한 항목. 작성 시각은 UTC이며 과거 데이터는 null일 수 있다. */
public record ReviewItem(
	long id,
	int rating,
	String content,
	LocalDateTime reviewedAt,
	long reviewerId,
	String reviewerNickname,
	String reviewerThumbnailImageUrl,
	List<StoredImage> images) {

	@QueryProjection
	public ReviewItem(long id, int rating, String content, LocalDateTime reviewedAt,
		long reviewerId, String reviewerNickname, String reviewerThumbnailImageUrl) {
		this(id, rating, content, reviewedAt, reviewerId, reviewerNickname, reviewerThumbnailImageUrl, List.of());
	}

	public ReviewItem withImages(List<StoredImage> images) {
		return new ReviewItem(id, rating, content, reviewedAt, reviewerId, reviewerNickname, reviewerThumbnailImageUrl,
			images);
	}
}
