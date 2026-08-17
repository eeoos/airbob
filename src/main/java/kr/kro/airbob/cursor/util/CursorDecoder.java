package kr.kro.airbob.cursor.util;

import java.io.IOException;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.cursor.dto.CursorPayload;
import kr.kro.airbob.cursor.exception.CursorDecodingException;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CursorDecoder {

	private final ObjectMapper objectMapper;

	public <T extends CursorPayload> T decode(
		String cursor,
		Class<T> cursorType
	) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}

		try {
			byte[] decoded = Base64.getDecoder().decode(cursor);
			T cursorData = objectMapper.readerFor(cursorType)
				.with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readValue(decoded);
			if (cursorData == null) {
				throw new IllegalArgumentException("커서 데이터가 없습니다.");
			}
			cursorData.validate();
			return cursorData;
		} catch (IOException | IllegalArgumentException exception) {
			throw new CursorDecodingException(exception);
		}
	}
}
