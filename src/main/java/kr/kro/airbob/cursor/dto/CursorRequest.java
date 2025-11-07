package kr.kro.airbob.cursor.dto;

import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CursorRequest {

	@Builder
	public record CursorPageRequest(
		Integer size,
		Long lastId,
		LocalDateTime lastCreatedAt
	) {
		public CursorPageRequest{
			if (size == null) {
				size = 20; // 기본값
			}
		}
	}

	@Builder
	public record ReviewCursorPageRequest(
		Integer size,
		Long lastId,
		LocalDateTime lastCreatedAt,
		Integer lastRating
	) {
	}
}
