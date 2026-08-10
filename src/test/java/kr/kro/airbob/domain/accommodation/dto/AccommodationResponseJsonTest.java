package kr.kro.airbob.domain.accommodation.dto;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
}
