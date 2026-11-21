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

import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;

import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.accommodation.dto.AmenityResponse;
import kr.kro.airbob.domain.accommodation.dto.PolicyResponse;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.review.dto.ReviewResponse;

@JsonTest
@DisplayName("숙소 상세 캐시 값 JSON 테스트")
class AccommodationDetailCacheValueJsonTest {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("운영 ObjectMapper로 시간과 중첩 record를 손실 없이 왕복한다")
	void roundTripsFullSnapshot() throws Exception {
		AccommodationDetailCacheValue expected = AccommodationDetailCacheValue.found(
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
				new AddressResponse.AddressSummaryInfo("대한민국", "서울특별시", "서울", "마포구"),
				new AddressResponse.Coordinate(37.5665, 126.9780),
				new MemberResponse.MemberInfo(2L, "host", "https://image.test/host"),
				new PolicyResponse.PolicyInfo(4, 1, 0),
				List.of(new AmenityResponse.AmenityInfo("WIFI", 1)),
				List.of(new ImageResponse.ImageInfo(3L, "https://image.test/3")),
				new ReviewResponse.ReviewSummary(8, new BigDecimal("4.75"))
			));

		String json = objectMapper.writeValueAsString(expected);
		AccommodationDetailCacheValue actual = objectMapper.readValue(
			json, AccommodationDetailCacheValue.class);

		assertThat(actual).isEqualTo(expected);
		assertThat(json).contains("\"check_in_time\":\"15:00:00\"");
	}
}
