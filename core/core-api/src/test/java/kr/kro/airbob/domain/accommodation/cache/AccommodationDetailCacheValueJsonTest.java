package kr.kro.airbob.domain.accommodation.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.core.domain.image.StoredImage;
import kr.kro.airbob.domain.accommodation.AccommodationDetailSnapshot;

@JsonTest
@DisplayName("숙소 상세 캐시 값 JSON 테스트")
class AccommodationDetailCacheValueJsonTest {

	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * 이미 Redis에 저장된 캐시 값의 JSON 모양. 클래스 이름은 저장하지 않으므로 타입을 옮겨도 되지만,
	 * 필드 이름이나 중첩 구조가 바뀌면 배포 직후 기존 캐시 값을 읽지 못한다.
	 */
	private static final String STORED_JSON = "{\"status\":\"FOUND\",\"snapshot\":{\"id\":1,\"name\":\"한강 뷰 숙소\","
		+ "\"description\":\"조용한 숙소\",\"type\":\"APARTMENT\",\"base_price\":120000,\"currency\":\"KRW\","
		+ "\"check_in_time\":\"15:00:00\",\"check_out_time\":\"11:00:00\",\"time_zone_id\":\"Asia/Seoul\","
		+ "\"address_summary\":{\"country\":\"대한민국\",\"state\":\"서울특별시\",\"city\":\"서울\",\"district\":\"마포구\"},"
		+ "\"coordinate\":{\"latitude\":37.5665,\"longitude\":126.978},"
		+ "\"host\":{\"id\":2,\"nickname\":\"host\",\"thumbnail_image_url\":\"https://image.test/host\"},"
		+ "\"policy\":{\"max_occupancy\":4,\"infant_occupancy\":1,\"pet_occupancy\":0},"
		+ "\"amenities\":[{\"type\":\"WIFI\",\"count\":1}],"
		+ "\"images\":[{\"id\":3,\"image_url\":\"https://image.test/3\"}],"
		+ "\"review_summary\":{\"total_count\":8,\"average_rating\":4.75}}}";

	@Test
	@DisplayName("운영 ObjectMapper로 시간과 중첩 record를 손실 없이 왕복한다")
	void roundTripsFullSnapshot() throws Exception {
		AccommodationDetailCacheValue expected = fullValue();

		String json = objectMapper.writeValueAsString(expected);
		AccommodationDetailCacheValue actual = objectMapper.readValue(
			json, AccommodationDetailCacheValue.class);

		assertThat(actual).isEqualTo(expected);
		assertThat(json).contains("\"check_in_time\":\"15:00:00\"");
	}

	@Test
	@DisplayName("이미 저장된 캐시 JSON과 같은 모양으로 쓰고 그 JSON을 읽는다")
	void keepsStoredJsonShape() throws Exception {
		AccommodationDetailCacheValue expected = fullValue();

		assertThat(objectMapper.writeValueAsString(expected)).isEqualTo(STORED_JSON);
		assertThat(objectMapper.readValue(STORED_JSON, AccommodationDetailCacheValue.class)).isEqualTo(expected);
	}

	private AccommodationDetailCacheValue fullValue() {
		return AccommodationDetailCacheValue.found(
			new AccommodationDetailSnapshot(
				1L,
				"한강 뷰 숙소",
				"조용한 숙소",
				"APARTMENT",
				120_000L,
				"KRW",
				LocalTime.of(15, 0),
				LocalTime.of(11, 0),
				"Asia/Seoul",
				new AccommodationDetailSnapshot.AddressSummary("대한민국", "서울특별시", "서울", "마포구"),
				new AccommodationDetailSnapshot.Coordinate(37.5665, 126.9780),
				new AccommodationDetailSnapshot.Host(2L, "host", "https://image.test/host"),
				new AccommodationDetailSnapshot.Policy(4, 1, 0),
				List.of(new AccommodationDetailSnapshot.Amenity("WIFI", 1)),
				List.of(new StoredImage(3L, "https://image.test/3")),
				new AccommodationDetailSnapshot.ReviewSummary(8, new BigDecimal("4.75"))
			));
	}
}
