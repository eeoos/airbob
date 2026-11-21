package kr.kro.airbob.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;

@JsonTest
@DisplayName("절대 시각 API 응답 계약 테스트")
class UtcResponseJsonTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-08-12T05:30:00Z");

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("숙소, 리뷰, 위시리스트의 절대 시각은 Z가 붙은 UTC로 반환한다")
	void serializesAbsoluteTimesWithUtcOffset() {
		AccommodationResponse.HostAccommodationInfo accommodation =
			AccommodationResponse.HostAccommodationInfo.builder().createdAt(OCCURRED_AT).build();
		ReviewResponse.ReviewInfo review = new ReviewResponse.ReviewInfo(
			1L, 5, "좋아요", OCCURRED_AT, null, List.of());
		WishlistResponse.WishlistInfo wishlist = WishlistResponse.WishlistInfo.builder()
			.createdAt(OCCURRED_AT)
			.build();
		WishlistAccommodationResponse.WishlistAccommodationInfo wishlistAccommodation =
			new WishlistAccommodationResponse.WishlistAccommodationInfo(
				1L, null, OCCURRED_AT, null, null, null, true);

		assertUtc(accommodation, "created_at", "createdAt");
		assertUtc(review, "reviewed_at", "reviewedAt");
		assertUtc(wishlist, "created_at", "createdAt");
		assertUtc(wishlistAccommodation, "created_at", "createdAt");
	}

	private void assertUtc(Object value, String fieldName, String camelCaseFieldName) {
		JsonNode json = objectMapper.valueToTree(value);
		assertThat(json.path(fieldName).asText()).isEqualTo("2026-08-12T05:30:00Z");
		assertThat(json.has(camelCaseFieldName)).isFalse();
	}
}
