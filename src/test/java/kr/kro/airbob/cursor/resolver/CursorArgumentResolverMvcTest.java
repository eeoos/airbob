package kr.kro.airbob.cursor.resolver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorData;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.ReviewCursorData;
import kr.kro.airbob.cursor.util.CursorDecoder;
import kr.kro.airbob.cursor.util.CursorEncoder;

@DisplayName("커서 요청 HTTP 계약 테스트")
class CursorArgumentResolverMvcTest {

	private MockMvc mockMvc;
	private CursorEncoder cursorEncoder;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		CursorDecoder cursorDecoder = new CursorDecoder(objectMapper);
		CursorParamArgumentResolver argumentResolver =
			new CursorParamArgumentResolver(cursorDecoder);
		ReviewCursorParamArgumentResolver reviewArgumentResolver =
			new ReviewCursorParamArgumentResolver(cursorDecoder);
		cursorEncoder = new CursorEncoder(objectMapper);

		mockMvc = MockMvcBuilders.standaloneSetup(new CursorTestController())
			.setCustomArgumentResolvers(argumentResolver, reviewArgumentResolver)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("숫자가 아닌 페이지 크기는 400으로 응답한다")
	void rejectsNonNumericPageSize() throws Exception {
		mockMvc.perform(get("/cursor")
				.param("size", "abc")
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("C006"));
	}

	@Test
	@DisplayName("손상된 커서는 400으로 응답한다")
	void rejectsMalformedCursor() throws Exception {
		mockMvc.perform(get("/cursor")
				.param("cursor", "%%%")
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("C010"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "51"})
	@DisplayName("허용 범위를 벗어난 페이지 크기는 400으로 응답한다")
	void rejectsOutOfRangePageSize(String size) throws Exception {
		mockMvc.perform(get("/cursor")
				.param("size", size)
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C006"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"1", "50"})
	@DisplayName("페이지 크기 경계값을 허용한다")
	void acceptsPageSizeBoundaries(String size) throws Exception {
		mockMvc.perform(get("/cursor")
				.param("size", size)
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.size").value(Integer.parseInt(size)));
	}

	@Test
	@DisplayName("일반 커서를 일반 요청 값으로 변환한다")
	void resolvesGenericCursorRequest() throws Exception {
		String cursor = cursorEncoder.encode(new CursorData(
			17L,
			LocalDateTime.of(2026, 8, 13, 12, 30)
		));

		mockMvc.perform(get("/cursor")
				.param("size", "7")
				.param("cursor", cursor)
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.size").value(7))
			.andExpect(jsonPath("$.data.lastId").value(17));
	}

	@Test
	@DisplayName("리뷰 커서를 리뷰 요청 값으로 변환한다")
	void resolvesReviewCursorRequest() throws Exception {
		String cursor = cursorEncoder.encode(new ReviewCursorData(
			23L,
			LocalDateTime.of(2026, 8, 13, 14, 15),
			4
		));

		mockMvc.perform(get("/review-cursor")
				.param("size", "7")
				.param("cursor", cursor)
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.size").value(7))
			.andExpect(jsonPath("$.data.lastId").value(23))
			.andExpect(jsonPath("$.data.lastRating").value(4));
	}

	@RestController
	private static class CursorTestController {

		@GetMapping("/cursor")
		ApiResponse<CursorRequest.CursorPageRequest> find(
			@CursorParam CursorRequest.CursorPageRequest request
		) {
			return ApiResponse.success(request);
		}

		@GetMapping("/review-cursor")
		ApiResponse<CursorRequest.ReviewCursorPageRequest> findReviews(
			@CursorParam CursorRequest.ReviewCursorPageRequest request
		) {
			return ApiResponse.success(request);
		}
	}
}
