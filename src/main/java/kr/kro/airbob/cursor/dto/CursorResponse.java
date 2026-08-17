package kr.kro.airbob.cursor.dto;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CursorResponse {

	@Builder
	public record PageInfo(
		boolean hasNext,
		String nextCursor,
		int currentSize
	) {
	}
}
