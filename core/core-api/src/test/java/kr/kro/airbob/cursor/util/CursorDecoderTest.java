package kr.kro.airbob.cursor.util;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.stream.Stream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.cursor.dto.CouponCampaignCursorData;
import kr.kro.airbob.cursor.dto.CursorData;
import kr.kro.airbob.cursor.dto.CursorPayload;
import kr.kro.airbob.cursor.dto.ReviewCursorData;
import kr.kro.airbob.cursor.exception.CursorEncodingException;

@DisplayName("커서 인코딩과 디코딩 테스트")
class CursorDecoderTest {

	private CursorEncoder cursorEncoder;
	private CursorDecoder cursorDecoder;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		cursorEncoder = new CursorEncoder(objectMapper);
		cursorDecoder = new CursorDecoder(objectMapper);
	}

	@Test
	@DisplayName("일반 커서를 왕복 변환한다")
	void roundTripsCursorData() {
		CursorData expected = new CursorData(
			17L,
			LocalDateTime.of(2026, 8, 13, 12, 30, 0, 123456000)
		);

		String encoded = cursorEncoder.encode(expected);

		assertThat(cursorDecoder.decode(encoded, CursorData.class))
			.isEqualTo(expected);
	}

	@Test
	@DisplayName("리뷰 커서를 왕복 변환한다")
	void roundTripsReviewCursorData() {
		ReviewCursorData expected = new ReviewCursorData(
			23L,
			LocalDateTime.of(2026, 8, 13, 14, 15, 0, 654321000),
			5
		);

		String encoded = cursorEncoder.encode(expected);

		assertThat(cursorDecoder.decode(encoded, ReviewCursorData.class))
			.isEqualTo(expected);
	}

	@Test
	@DisplayName("캠페인 커서는 발급 시작 시각의 마이크로초를 보존하며 왕복 변환한다")
	void roundTripsCampaignCursorData() {
		CouponCampaignCursorData expected = new CouponCampaignCursorData(
			29L,
			LocalDateTime.of(2026, 8, 13, 15, 45, 0, 123456000)
		);

		String encoded = cursorEncoder.encode(expected);

		assertThat(cursorDecoder.decode(encoded, CouponCampaignCursorData.class))
			.isEqualTo(expected);
		assertThat(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
			.isEqualTo("{\"id\":29,\"last_issue_start_at\":\"2026-08-13T15:45:00.123456\"}");
	}

	@Test
	@DisplayName("커서가 없으면 첫 페이지로 처리한다")
	void returnsNullWhenCursorIsAbsent() {
		assertThat(cursorDecoder.decode(null, CursorData.class)).isNull();
		assertThat(cursorDecoder.decode("   ", CursorData.class)).isNull();
	}

	@Test
	@DisplayName("필수 값이 없는 커서는 인코딩하지 않는다")
	void rejectsIncompleteCursorWhenEncoding() {
		CursorData incompleteCursor = new CursorData(
			17L,
			null
		);

		assertThatThrownBy(() -> cursorEncoder.encode(incompleteCursor))
			.isInstanceOf(CursorEncodingException.class);
	}

	@ParameterizedTest
	@MethodSource("invalidTypedCursors")
	@DisplayName("모든 커서 종류는 필수 경계 값이 유효하지 않으면 인코딩하지 않는다")
	void rejectsInvalidTypedCursorWhenEncoding(CursorPayload cursor) {
		assertThatThrownBy(() -> cursorEncoder.encode(cursor))
			.isInstanceOf(CursorEncodingException.class);
	}

	private static Stream<CursorPayload> invalidTypedCursors() {
		LocalDateTime time = LocalDateTime.of(2026, 8, 13, 12, 30);
		return Stream.of(
			new CursorData(null, time),
			new CursorData(0L, time),
			new ReviewCursorData(23L, null, 4),
			new ReviewCursorData(null, time, 4),
			new ReviewCursorData(-1L, time, 4),
			new ReviewCursorData(23L, time, null),
			new CouponCampaignCursorData(29L, null),
			new CouponCampaignCursorData(null, time),
			new CouponCampaignCursorData(0L, time),
			new CouponCampaignCursorData(-1L, time)
		);
	}

	@ParameterizedTest
	@MethodSource("invalidTypedCursorJson")
	@DisplayName("리뷰와 캠페인 커서의 누락된 필수 값과 유효하지 않은 ID를 거부한다")
	void rejectsInvalidTypedCursorWhenDecoding(String json, Class<? extends CursorPayload> type) {
		assertBadRequestCursor(() -> cursorDecoder.decode(encodeJson(json), type));
	}

	private static Stream<Arguments> invalidTypedCursorJson() {
		return Stream.of(
			Arguments.of("{\"id\":23,\"last_rating\":4}", ReviewCursorData.class),
			Arguments.of("{\"id\":29}", CouponCampaignCursorData.class),
			Arguments.of(
				"{\"last_issue_start_at\":\"2026-08-13T15:45:00\"}",
				CouponCampaignCursorData.class
			),
			Arguments.of(
				"{\"id\":0,\"last_issue_start_at\":\"2026-08-13T15:45:00\"}",
				CouponCampaignCursorData.class
			),
			Arguments.of(
				"{\"id\":-1,\"last_issue_start_at\":\"2026-08-13T15:45:00\"}",
				CouponCampaignCursorData.class
			)
		);
	}

	@Test
	@DisplayName("Base64 형식이 아닌 커서를 거부한다")
	void rejectsMalformedBase64Cursor() {
		assertBadRequestCursor(() -> cursorDecoder.decode("%%%", CursorData.class));
	}

	@Test
	@DisplayName("키셋 필드가 빠진 커서를 거부한다")
	void rejectsIncompleteCursor() {
		String incompleteCursor = encodeJson("{\"id\":17}");

		assertBadRequestCursor(() -> cursorDecoder.decode(incompleteCursor, CursorData.class));
	}

	@Test
	@DisplayName("JSON null 커서를 거부한다")
	void rejectsJsonNullCursor() {
		assertBadRequestCursor(() ->
			cursorDecoder.decode(encodeJson("null"), CursorData.class)
		);
	}

	@Test
	@DisplayName("유효한 JSON 뒤에 값이 이어진 커서를 거부한다")
	void rejectsTrailingJsonValue() {
		String cursorWithTrailingValue = encodeJson(
			"{\"id\":17,\"last_created_at\":\"2026-08-13T12:30:00\"} true"
		);

		assertBadRequestCursor(() ->
			cursorDecoder.decode(cursorWithTrailingValue, CursorData.class)
		);
	}

	@ParameterizedTest
	@ValueSource(longs = {0L, -1L})
	@DisplayName("양수가 아닌 ID 커서를 거부한다")
	void rejectsNonPositiveCursorId(long id) {
		String cursor = encodeJson(
			"{\"id\":" + id
				+ ",\"last_created_at\":\"2026-08-13T12:30:00\"}"
		);

		assertBadRequestCursor(() -> cursorDecoder.decode(cursor, CursorData.class));
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 6})
	@DisplayName("1부터 5 사이가 아닌 리뷰 평점 커서를 거부한다")
	void rejectsOutOfRangeReviewRating(int rating) {
		String cursor = encodeJson(
			"{\"id\":23,\"last_created_at\":\"2026-08-13T14:15:00\","
				+ "\"last_rating\":" + rating + "}"
		);

		assertBadRequestCursor(() ->
			cursorDecoder.decode(cursor, ReviewCursorData.class)
		);
	}

	@Test
	@DisplayName("이전 서버가 발급한 SNAKE_CASE 커서를 해석한다")
	void decodesLegacySnakeCaseCursor() {
		String legacyCursor = encodeJson(
			"{\"id\":17,\"last_created_at\":\"2026-08-13T12:30:00\"}"
		);

		assertThat(cursorDecoder.decode(legacyCursor, CursorData.class))
			.isEqualTo(new CursorData(
				17L,
				LocalDateTime.of(2026, 8, 13, 12, 30)
			));
	}

	@Test
	@DisplayName("이전 서버가 발급한 리뷰 커서를 해석한다")
	void decodesLegacyReviewCursor() {
		String legacyCursor = encodeJson(
			"{\"id\":23,\"last_created_at\":\"2026-08-13T14:15:00\","
				+ "\"last_rating\":4}"
		);

		assertThat(cursorDecoder.decode(legacyCursor, ReviewCursorData.class))
			.isEqualTo(new ReviewCursorData(
				23L,
				LocalDateTime.of(2026, 8, 13, 14, 15),
				4
			));
	}

	@Test
	@DisplayName("리뷰 커서를 일반 커서로 사용할 수 없다")
	void rejectsReviewCursorForGenericRequest() {
		String reviewCursor = cursorEncoder.encode(new ReviewCursorData(
			23L,
			LocalDateTime.of(2026, 8, 13, 14, 15),
			4
		));

		assertBadRequestCursor(() -> cursorDecoder.decode(reviewCursor, CursorData.class));
	}

	@Test
	@DisplayName("일반 커서를 리뷰 커서로 사용할 수 없다")
	void rejectsGenericCursorForReviewRequest() {
		String cursor = cursorEncoder.encode(new CursorData(
			17L,
			LocalDateTime.of(2026, 8, 13, 12, 30)
		));

		assertBadRequestCursor(() -> cursorDecoder.decode(cursor, ReviewCursorData.class));
	}

	@Test
	@DisplayName("캠페인 커서는 일반 및 리뷰 커서와 서로 교환할 수 없다")
	void rejectsCampaignCursorForOtherRequestsAndViceVersa() {
		LocalDateTime time = LocalDateTime.of(2026, 8, 13, 12, 30);
		String campaignCursor = cursorEncoder.encode(new CouponCampaignCursorData(29L, time));
		String genericCursor = cursorEncoder.encode(new CursorData(17L, time));
		String reviewCursor = cursorEncoder.encode(new ReviewCursorData(23L, time, 4));

		assertBadRequestCursor(() -> cursorDecoder.decode(campaignCursor, CursorData.class));
		assertBadRequestCursor(() -> cursorDecoder.decode(campaignCursor, ReviewCursorData.class));
		assertBadRequestCursor(() -> cursorDecoder.decode(genericCursor, CouponCampaignCursorData.class));
		assertBadRequestCursor(() -> cursorDecoder.decode(reviewCursor, CouponCampaignCursorData.class));
	}

	private String encodeJson(String json) {
		return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private void assertBadRequestCursor(ThrowingCallable callable) {
		assertThatThrownBy(callable)
			.isInstanceOfSatisfying(BaseException.class, exception -> {
				assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.CURSOR_DECODING_ERROR);
				assertThat(exception.getErrorCode().getStatus().is4xxClientError())
					.isTrue();
			});
	}
}
