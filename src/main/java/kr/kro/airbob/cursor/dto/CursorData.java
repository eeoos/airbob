package kr.kro.airbob.cursor.dto;

import java.time.LocalDateTime;

public record CursorData(
	Long id,
	LocalDateTime lastCreatedAt
) implements CursorPayload {
}
