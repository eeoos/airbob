package kr.kro.airbob.domain.reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import kr.kro.airbob.domain.reservation.entity.ReservationQuote;

/** 새로 만든 견적. 재고는 잡지 않으며, 결제 금액이 0보다 크면 결제가 필요하다. */
public record ReservationQuoteDetail(
	UUID quoteUid,
	Long accommodationId,
	String orderName,
	LocalDate checkIn,
	LocalDate checkOut,
	Integer guestCount,
	Long nightlyPrice,
	Long nights,
	Long subtotal,
	Long discountAmount,
	Long amount,
	String currency,
	boolean paymentRequired,
	Instant serverTime
) {

	public static ReservationQuoteDetail of(ReservationQuote quote, Instant serverTime) {
		return new ReservationQuoteDetail(
			quote.getQuoteUid(),
			quote.getAccommodationId(),
			quote.getOrderName(),
			quote.getCheckInDate(),
			quote.getCheckOutDate(),
			quote.getGuestCount(),
			quote.getNightlyPrice(),
			quote.getNights(),
			quote.getSubtotal(),
			quote.getDiscountAmount(),
			quote.getAmount(),
			quote.getCurrency(),
			quote.getAmount() > 0,
			serverTime);
	}
}
