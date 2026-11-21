package kr.kro.airbob.cursor.dto;

import java.time.LocalDateTime;

public record CursorData(
	Long id,
	LocalDateTime lastCreatedAt
) implements CursorPayload {

	@Override
	public void validate() {
		CursorPayload.super.validate();
		if (lastCreatedAt == null) {
			throw new IllegalArgumentException("커서 경계 값이 유효하지 않습니다.");
		}
	}
}
