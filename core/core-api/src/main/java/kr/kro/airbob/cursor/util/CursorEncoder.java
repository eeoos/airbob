package kr.kro.airbob.cursor.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.cursor.dto.CursorPayload;
import kr.kro.airbob.cursor.exception.CursorEncodingException;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CursorEncoder {

	private final ObjectMapper objectMapper;

	public String encode(CursorPayload cursor) {
		if (cursor == null) {
			return null;
		}

		try {
			cursor.validate();
			String json = objectMapper.writeValueAsString(cursor);
			return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		} catch (JsonProcessingException | IllegalArgumentException exception) {
			throw new CursorEncodingException("커서 인코딩 실패: " + exception.getMessage());
		}
	}
}
