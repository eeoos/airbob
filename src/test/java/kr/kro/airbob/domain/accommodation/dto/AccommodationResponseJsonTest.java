package kr.kro.airbob.domain.accommodation.dto;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;

@JsonTest
@DisplayName("숙소 상세 응답 JSON 테스트")
class AccommodationResponseJsonTest {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("예약 가능 기간과 예약 불가 구간을 snake case로 반환한다")
	void serializesBookingWindowAndUnavailableRanges() {
		AccommodationResponse.DetailInfo response = AccommodationResponse.DetailInfo.builder()
			.bookingWindowStartInclusive(LocalDate.of(2026, 8, 1))
			.bookingWindowEndExclusive(LocalDate.of(2026, 11, 1))
			.unavailableRanges(List.of(
				new AccommodationResponse.UnavailableDateRange(
					LocalDate.of(2026, 8, 1),
					LocalDate.of(2026, 8, 4))))
			.build();

		JsonNode json = objectMapper.valueToTree(response);

		assertThat(json.path("booking_window_start_inclusive").asText())
			.isEqualTo("2026-08-01");
		assertThat(json.path("booking_window_end_exclusive").asText())
			.isEqualTo("2026-11-01");
		assertThat(json.path("unavailable_ranges").path(0).path("start_date").asText())
			.isEqualTo("2026-08-01");
		assertThat(json.path("unavailable_ranges").path(0).path("end_date_exclusive").asText())
			.isEqualTo("2026-08-04");
		assertThat(json.has("unavailable_dates")).isFalse();
	}

	@Test
	@DisplayName("숙소 현지 시간대 식별자를 snake case로 반환한다")
	void serializesAccommodationTimeZoneIdInSnakeCase() {
		Accommodation accommodation = mock(Accommodation.class);
		when(accommodation.getTimeZoneId()).thenReturn("America/New_York");
		AccommodationResponse.DetailInfo response = AccommodationResponse.DetailInfo.from(
			accommodation,
			LocalDate.of(2026, 8, 11),
			LocalDate.of(2026, 11, 11),
			List.of(),
			false,
			List.of(),
			List.of(),
			null
		);

		JsonNode json = objectMapper.valueToTree(response);

		assertThat(json.path("time_zone_id").asText()).isEqualTo("America/New_York");
		assertThat(json.has("timeZoneId")).isFalse();
	}
}
