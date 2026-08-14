package kr.kro.airbob.cursor.dto;

import java.time.LocalDateTime;

public interface CursorPayload {

	Long id();

	LocalDateTime lastCreatedAt();

	default void validate() {
		if (id() == null || id() < 1 || lastCreatedAt() == null) {
			throw new IllegalArgumentException("커서 경계 값이 유효하지 않습니다.");
		}
	}
}
