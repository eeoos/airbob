package kr.kro.airbob.domain.accommodation.dto;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.member.entity.Member;

@JsonTest
@DisplayName("숙소 상세 응답 JSON 테스트")
class AccommodationResponseJsonTest {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("호스트 편집 응답은 후기 요약·호스트·좌표 없이 편집 계약을 반환한다")
	void serializesHostEditorContractWithoutPublicDetailFields() throws Exception {
		Accommodation accommodation = Accommodation.builder()
			.id(31L).name("합정 테스트 숙소").description("조용한 숙소").type("APARTMENT")
			.basePrice(125_000L).currency("KRW")
			.checkInTime(LocalTime.of(15, 0)).checkOutTime(LocalTime.of(11, 0)).timeZoneId("Asia/Seoul")
			.member(Member.builder().id(202L).nickname("합정 호스트").build())
			.address(Address.builder().country("대한민국").state("서울특별시").city("서울")
				.district("마포구").street("월드컵북로").detail("101호").postalCode("04000")
				.latitude(37.556).longitude(126.923).build())
			.occupancyPolicy(OccupancyPolicy.builder().maxOccupancy(4).infantOccupancy(1).petOccupancy(0).build())
			.build();
		AccommodationResponse.HostDetail response = AccommodationResponse.HostDetail.from(accommodation,
			List.of(new AmenityResponse.AmenityInfo("WIFI", 1)),
			List.of(new ImageResponse.ImageInfo(301L, "/room-301.png")));

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));
		try (var fixture = new ClassPathResource("contracts/host-accommodation-editor.json").getInputStream()) {
			assertThat(json).isEqualTo(objectMapper.readTree(fixture));
		}
		assertThat(json.has("host")).isFalse();
		assertThat(json.has("coordinate")).isFalse();
		assertThat(json.has("review_summary")).isFalse();
	}

	@Test
	@DisplayName("예약 가능 기간과 예약 불가 구간을 snake case로 반환한다")
	void serializesBookingWindowAndUnavailableRanges() {
		AccommodationResponse.Availability response = AccommodationResponse.Availability.builder()
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
	@DisplayName("숙소 상세 응답에는 예약 가능 정보가 포함되지 않는다")
	void accommodationDetailExcludesAvailability() {
		AccommodationResponse.DetailInfo response = AccommodationResponse.DetailInfo.builder()
			.build();

		JsonNode json = objectMapper.valueToTree(response);

		assertThat(json.has("booking_window_start_inclusive")).isFalse();
		assertThat(json.has("booking_window_end_exclusive")).isFalse();
		assertThat(json.has("unavailable_ranges")).isFalse();
	}

	@Test
	@DisplayName("숙소 현지 시간대 식별자를 snake case로 반환한다")
	void serializesAccommodationTimeZoneIdInSnakeCase() {
		AccommodationDetailSnapshot snapshot = new AccommodationDetailSnapshot(
			1L, "숙소", null, null, null, null, null, null,
			"America/New_York", null, null, null, null, List.of(), List.of(), null);
		AccommodationResponse.DetailInfo response = AccommodationResponse.DetailInfo.from(
			snapshot, false
		);

		JsonNode json = objectMapper.valueToTree(response);

		assertThat(json.path("time_zone_id").asText()).isEqualTo("America/New_York");
		assertThat(json.has("timeZoneId")).isFalse();
	}

	@Test
	@DisplayName("호스트 상세에도 숙소 현지 시간대 식별자를 반환한다")
	void serializesHostAccommodationTimeZoneId() {
		Accommodation accommodation = mock(Accommodation.class);
		when(accommodation.getTimeZoneId()).thenReturn("Europe/Paris");
		AccommodationResponse.HostDetail response = AccommodationResponse.HostDetail.from(
			accommodation,
			List.of(),
			List.of()
		);

		JsonNode json = objectMapper.valueToTree(response);

		assertThat(json.path("time_zone_id").asText()).isEqualTo("Europe/Paris");
		assertThat(json.has("timeZoneId")).isFalse();
	}

	@Test
	@DisplayName("최근 조회 시각을 UTC Instant 형식으로 반환한다")
	void serializesRecentlyViewedAtAsUtcInstant() {
		AccommodationResponse.RecentlyViewedAccommodationInfo response =
			AccommodationResponse.RecentlyViewedAccommodationInfo.builder()
				.viewedAt(Instant.parse("2026-08-12T05:30:00Z"))
				.build();

		JsonNode json = objectMapper.valueToTree(response);

		assertThat(json.path("viewed_at").asText()).isEqualTo("2026-08-12T05:30:00Z");
	}
}
