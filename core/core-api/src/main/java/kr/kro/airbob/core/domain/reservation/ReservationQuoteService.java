package kr.kro.airbob.core.domain.reservation;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.domain.accommodation.Accommodation;
import kr.kro.airbob.core.domain.accommodation.AccommodationNotFoundException;
import kr.kro.airbob.core.domain.accommodation.AccommodationReader;
import kr.kro.airbob.core.domain.accommodation.AccommodationStatus;
import kr.kro.airbob.core.domain.accommodation.OccupancyPolicy;
import kr.kro.airbob.core.domain.coupon.CouponUsageManager;
import kr.kro.airbob.core.domain.member.MemberReader;
import kr.kro.airbob.core.domain.reservation.inventory.ReservationInventoryManager;
import kr.kro.airbob.core.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.core.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.core.domain.reservation.policy.ReservationStayPricePolicy;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReservationQuoteService {

	private final AccommodationReader accommodationReader;
	private final MemberReader memberReader;
	private final ReservationInventoryManager inventoryManager;
	private final ReservationQuoteWriter quoteWriter;
	private final CouponUsageManager couponUsageManager;
	private final BookingWindowProvider bookingWindowProvider;
	private final Clock clock;

	@Transactional
	public ReservationQuoteDetail createQuote(NewReservationQuote request, Long memberId) {
		memberReader.readActive(memberId);

		Accommodation accommodation = accommodationReader.findQuoteSnapshotByIdAndStatus(
			request.accommodationId(), AccommodationStatus.PUBLISHED)
			.orElseThrow(AccommodationNotFoundException::new);
		validateOccupancy(accommodation, request.guestCount());
		if (!request.checkOutDate().isAfter(request.checkInDate())) {
			throw new InvalidReservationDateException();
		}

		Instant now = clock.instant();
		BookingWindow bookingWindow = bookingWindowProvider.currentFor(
			accommodation.getTimeZoneId(), now);
		if (!bookingWindow.containsStay(request.checkInDate(), request.checkOutDate())) {
			throw new ReservationOutsideBookingWindowException();
		}

		Instant checkInAt = Reservation.resolveCheckInAt(accommodation, request.checkInDate());
		Reservation.resolveCheckOutAt(accommodation, request.checkOutDate());
		if (!now.isBefore(checkInAt)) {
			throw new ReservationCheckInClosedException();
		}
		if (!inventoryManager.isRangeAvailableSnapshot(
			request.accommodationId(), request.checkInDate(), request.checkOutDate(), now)) {
			throw new ReservationConflictException();
		}

		ReservationStayPricePolicy.StayPrice stayPrice = ReservationStayPricePolicy.calculate(
			accommodation.getBasePrice(), request.checkInDate(), request.checkOutDate());
		long discountAmount = request.couponId() == null
			? 0L
			: couponUsageManager.preview(
				memberId, request.couponId(), stayPrice.subtotal());
		String currency = accommodation.bookingCurrency();

		ReservationQuote quote = ReservationQuote.create(
			memberId,
			request,
			accommodation.getName(),
			currency,
			stayPrice,
			discountAmount,
			now);
		quoteWriter.create(quote);
		return ReservationQuoteDetail.of(quote, now);
	}

	private void validateOccupancy(Accommodation accommodation, Integer guestCount) {
		OccupancyPolicy occupancyPolicy = accommodation.getOccupancyPolicy();
		if (guestCount == null
			|| occupancyPolicy == null
			|| occupancyPolicy.getMaxOccupancy() == null
			|| guestCount > occupancyPolicy.getMaxOccupancy()) {
			throw new ReservationOccupancyExceededException();
		}
	}
}
