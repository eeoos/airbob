package kr.kro.airbob.cursor.resolver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CouponCampaignCursorData;
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
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		CursorDecoder cursorDecoder = new CursorDecoder(objectMapper);
		CursorParamArgumentResolver argumentResolver =
			new CursorParamArgumentResolver(cursorDecoder);
		ReviewCursorParamArgumentResolver reviewArgumentResolver =
			new ReviewCursorParamArgumentResolver(cursorDecoder);
		CouponCampaignCursorParamArgumentResolver campaignArgumentResolver =
			new CouponCampaignCursorParamArgumentResolver(cursorDecoder);
		cursorEncoder = new CursorEncoder(objectMapper);

		mockMvc = MockMvcBuilders.standaloneSetup(new CursorTestController())
			.setCustomArgumentResolvers(argumentResolver, reviewArgumentResolver, campaignArgumentResolver)
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@ParameterizedTest
	@ValueSource(strings = {"/cursor", "/review-cursor", "/campaign-cursor"})
	@DisplayName("숫자가 아닌 페이지 크기는 400으로 응답한다")
	void rejectsNonNumericPageSize(String path) throws Exception {
		mockMvc.perform(get(path)
				.param("size", "abc")
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("C006"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/cursor", "/review-cursor", "/campaign-cursor"})
	@DisplayName("손상된 커서는 400으로 응답한다")
	void rejectsMalformedCursor(String path) throws Exception {
		mockMvc.perform(get(path)
				.param("cursor", "%%%")
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("C010"));
	}

	@ParameterizedTest
	@CsvSource({
		"/cursor, 0", "/cursor, 51",
		"/review-cursor, 0", "/review-cursor, 51",
		"/campaign-cursor, 0", "/campaign-cursor, 51"
	})
	@DisplayName("허용 범위를 벗어난 페이지 크기는 400으로 응답한다")
	void rejectsOutOfRangePageSize(String path, String size) throws Exception {
		mockMvc.perform(get(path)
				.param("size", size)
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C006"));
	}

	@ParameterizedTest
	@CsvSource({
		"/cursor, 1", "/cursor, 50",
		"/review-cursor, 1", "/review-cursor, 50",
		"/campaign-cursor, 1", "/campaign-cursor, 50"
	})
	@DisplayName("페이지 크기 경계값을 허용한다")
	void acceptsPageSizeBoundaries(String path, String size) throws Exception {
		mockMvc.perform(get(path)
				.param("size", size)
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.size").value(Integer.parseInt(size)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/cursor", "/review-cursor", "/campaign-cursor"})
	@DisplayName("페이지 인자를 생략하면 크기 20의 첫 페이지로 변환한다")
	void defaultsToFirstPage(String path) throws Exception {
		mockMvc.perform(get(path)
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.size").value(20))
			.andExpect(jsonPath("$.data.last_id").isEmpty());
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
			.andExpect(jsonPath("$.data.last_id").value(17))
			.andExpect(jsonPath("$.data.last_created_at").value("2026-08-13T12:30:00"));
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
			.andExpect(jsonPath("$.data.last_id").value(23))
			.andExpect(jsonPath("$.data.last_rating").value(4));
	}

	@Test
	@DisplayName("캠페인 커서의 발급 시작 시각과 마이크로초를 요청 값으로 변환한다")
	void resolvesCampaignCursorRequest() throws Exception {
		String cursor = cursorEncoder.encode(new CouponCampaignCursorData(
			29L,
			LocalDateTime.of(2026, 8, 13, 15, 45, 0, 123456000)
		));

		mockMvc.perform(get("/campaign-cursor")
				.param("size", "7")
				.param("cursor", cursor)
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.size").value(7))
			.andExpect(jsonPath("$.data.last_id").value(29))
			.andExpect(jsonPath("$.data.last_issue_start_at").value("2026-08-13T15:45:00.123456"));
	}

	@Test
	@DisplayName("캠페인 목록에서 일반 커서를 사용하면 400으로 응답한다")
	void rejectsGenericCursorForCampaignRequest() throws Exception {
		String cursor = cursorEncoder.encode(new CursorData(
			17L,
			LocalDateTime.of(2026, 8, 13, 12, 30)
		));

		mockMvc.perform(get("/campaign-cursor")
				.param("cursor", cursor)
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C010"));
	}

	@Test
	@DisplayName("내 쿠폰에 사용하는 일반 커서 요청에서 캠페인 커서를 거부한다")
	void rejectsCampaignCursorForGenericRequest() throws Exception {
		String cursor = cursorEncoder.encode(new CouponCampaignCursorData(
			29L,
			LocalDateTime.of(2026, 8, 13, 15, 45)
		));

		mockMvc.perform(get("/cursor")
				.param("cursor", cursor)
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C010"));
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

		@GetMapping("/campaign-cursor")
		ApiResponse<CursorRequest.CouponCampaignCursorPageRequest> findCampaigns(
			@CursorParam CursorRequest.CouponCampaignCursorPageRequest request
		) {
			return ApiResponse.success(request);
		}
	}
}
