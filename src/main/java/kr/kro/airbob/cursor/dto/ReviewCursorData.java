package kr.kro.airbob.cursor.dto;

import java.time.LocalDateTime;

public record ReviewCursorData(
	Long id,
	LocalDateTime lastCreatedAt,
	Integer lastRating
) implements CursorPayload {

	@Override
	public void validate() {
		CursorPayload.super.validate();
		if (lastRating == null || lastRating < 1 || lastRating > 5) {
			throw new IllegalArgumentException("리뷰 평점 커서 값이 유효하지 않습니다.");
		}
	}
}
