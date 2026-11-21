package kr.kro.airbob.cursor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CursorResponse {

	@Builder
	@Schema(name = "CursorResponsePageInfo")
	public record PageInfo(
		@Schema(description = "다음 페이지 존재 여부", example = "false")
		boolean hasNext,
		@Schema(description = "다음 요청의 cursor 값. 마지막 페이지에서는 null입니다.", nullable = true)
		String nextCursor,
		@Schema(description = "이번 응답의 실제 항목 수", example = "1")
		int currentSize
	) {
	}
}
