package kr.kro.airbob.cursor.dto;

public interface CursorPayload {

	Long id();

	default void validate() {
		if (id() == null || id() < 1) {
			throw new IllegalArgumentException("커서 경계 값이 유효하지 않습니다.");
		}
	}
}
