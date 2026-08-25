package kr.kro.airbob.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.policy.ReservationQuotePolicy;
import kr.kro.airbob.domain.reservation.policy.ReservationStayPricePolicy;

@DisplayName("예약 견적 엔티티 테스트")
class ReservationQuoteTest {

	private static final long MEMBER_ID = 7L;
	private static final long ACCOMMODATION_ID = 31L;
	private static final long COUPON_ID = 55L;
	private static final Instant QUOTED_AT = Instant.parse("2026-08-25T03:00:00Z");

	private ReservationRequest.Quote request;
	private ReservationStayPricePolicy.StayPrice stayPrice;
	private ReservationQuote quote;

	@BeforeEach
	void setUp() {
		request = new ReservationRequest.Quote(
			ACCOMMODATION_ID,
			LocalDate.of(2026, 9, 10),
			LocalDate.of(2026, 9, 13),
			2,
			COUPON_ID
		);
		stayPrice = new ReservationStayPricePolicy.StayPrice(120_000L, 3L, 360_000L);
		quote = ReservationQuote.create(
			MEMBER_ID,
			request,
			"한강 전망 숙소",
			"KRW",
			stayPrice,
			30_000L,
			QUOTED_AT,
			ReservationQuotePolicy.defaultPolicy()
		);
	}

	@Test
	@DisplayName("견적은 회원·숙박 조건·쿠폰·가격을 결제 전 스냅샷으로 보관한다")
	void snapshotsQuoteInputsAndExactPrice() {
		assertThat(quote.getQuoteUid()).isNotNull();
		assertThat(quote.getMemberId()).isEqualTo(MEMBER_ID);
		assertThat(quote.getAccommodationId()).isEqualTo(ACCOMMODATION_ID);
		assertThat(quote.getOrderName()).isEqualTo("한강 전망 숙소");
		assertThat(quote.getCheckInDate()).isEqualTo(request.checkInDate());
		assertThat(quote.getCheckOutDate()).isEqualTo(request.checkOutDate());
		assertThat(quote.getGuestCount()).isEqualTo(2);
		assertThat(quote.getCouponId()).isEqualTo(COUPON_ID);
		assertThat(quote.getNightlyPrice()).isEqualTo(120_000L);
		assertThat(quote.getNights()).isEqualTo(3L);
		assertThat(quote.getSubtotal()).isEqualTo(360_000L);
		assertThat(quote.getDiscountAmount()).isEqualTo(30_000L);
		assertThat(quote.getAmount()).isEqualTo(330_000L);
		assertThat(quote.getCurrency()).isEqualTo("KRW");
		assertThat(quote.getExpiresAt()).isEqualTo(QUOTED_AT.plusSeconds(5 * 60));
		assertThat(quote.getReservationId()).isNull();
	}

	@Test
	@DisplayName("견적은 만료 시각 바로 전까지만 checkout에 사용할 수 있다")
	void expiresAtTheExactBoundary() {
		assertThat(quote.isExpiredAt(quote.getExpiresAt().minusNanos(1))).isFalse();
		assertThat(quote.isExpiredAt(quote.getExpiresAt())).isTrue();
	}

	@Test
	@DisplayName("checkout 재검증은 단가·숙박일·소계·할인액이 모두 같은 견적만 허용한다")
	void matchesOnlyTheExactPricingSnapshot() {
		assertThat(quote.matchesPricing(stayPrice, 30_000L)).isTrue();
		assertThat(quote.matchesPricing(
			new ReservationStayPricePolicy.StayPrice(180_000L, 2L, 360_000L),
			30_000L
		)).isFalse();
		assertThat(quote.matchesPricing(stayPrice, 29_999L)).isFalse();
	}

	@Test
	@DisplayName("checkout이 만든 예약을 견적에 연결할 수 있다")
	void attachesTheCreatedReservation() {
		Instant checkedOutAt = QUOTED_AT.plusSeconds(30);

		quote.attachReservation(91L, checkedOutAt);

		assertThat(quote.getReservationId()).isEqualTo(91L);
		assertThat(quote.getCheckedOutAt()).isEqualTo(checkedOutAt);
	}
}
