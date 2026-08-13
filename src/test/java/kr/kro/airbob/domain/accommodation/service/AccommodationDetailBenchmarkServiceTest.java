package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.accommodation.dto.AmenityResponse;
import kr.kro.airbob.domain.accommodation.dto.PolicyResponse;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.review.dto.ReviewResponse;

@DisplayName("숙소 상세 before 벤치마크 서비스 테스트")
class AccommodationDetailBenchmarkServiceTest {

	@Test
	@DisplayName("before 조회는 캐시 적용 전과 동일하게 하나의 read-only 트랜잭션을 사용한다")
	void preservesOriginalReadTransactionBoundary() throws Exception {
		Transactional transactional = AccommodationDetailBenchmarkService.class
			.getDeclaredMethod("findAccommodationBefore", Long.class, Long.class)
			.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.readOnly()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@DisplayName("캐시 없이 공유 상세와 요청자의 찜 여부를 조회해 응답을 만든다")
	void loadsDetailDirectlyAndPreservesWishlist(boolean isInWishlist) {
		AccommodationDetailReader reader = mock(AccommodationDetailReader.class);
		AccommodationDetailBenchmarkService service = new AccommodationDetailBenchmarkService(reader);
		AccommodationDetailSnapshot snapshot = snapshot();
		given(reader.load(10L)).willReturn(snapshot);
		given(reader.isInWishlist(10L, 7L)).willReturn(isInWishlist);

		AccommodationResponse.DetailInfo response = service.findAccommodationBefore(10L, 7L);

		assertThat(response.id()).isEqualTo(10L);
		assertThat(response.name()).isEqualTo("한강 뷰 숙소");
		assertThat(response.isInWishlist()).isEqualTo(isInWishlist);
		assertThat(response.amenities()).containsExactly(new AmenityResponse.AmenityInfo("WIFI", 1));
		assertThat(response.images()).containsExactly(new ImageResponse.ImageInfo(3L, "https://image.test/3"));
		var ordered = inOrder(reader);
		ordered.verify(reader).load(10L);
		ordered.verify(reader).isInWishlist(10L, 7L);
	}

	@Test
	@DisplayName("비로그인 before 조회는 찜 저장소를 조회하지 않는다")
	void anonymousRequestSkipsWishlistLookup() {
		AccommodationDetailReader reader = mock(AccommodationDetailReader.class);
		AccommodationDetailBenchmarkService service = new AccommodationDetailBenchmarkService(reader);
		given(reader.load(10L)).willReturn(snapshot());

		AccommodationResponse.DetailInfo response = service.findAccommodationBefore(10L, null);

		assertThat(response.isInWishlist()).isFalse();
		then(reader).should(never()).isInWishlist(anyLong(), anyLong());
	}

	private AccommodationDetailSnapshot snapshot() {
		return new AccommodationDetailSnapshot(
			10L,
			"한강 뷰 숙소",
			"조용한 숙소",
			"apartment",
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
		);
	}
}
