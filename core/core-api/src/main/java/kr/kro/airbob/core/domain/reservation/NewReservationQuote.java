package kr.kro.airbob.core.domain.reservation;

import java.time.LocalDate;

/** 견적 요청 내용. 날짜는 숙소 현지 날짜이고 체크아웃일 밤은 숙박에 넣지 않는다. 쿠폰을 쓰지 않으면 couponId는 null이다. */
public record NewReservationQuote(
	Long accommodationId,
	LocalDate checkInDate,
	LocalDate checkOutDate,
	Integer guestCount,
	Long couponId
) {
}
