package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationQuote;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import kr.kro.airbob.domain.reservation.exception.ReservationCheckInClosedException;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationOccupancyExceededException;
import kr.kro.airbob.domain.reservation.exception.ReservationOutsideBookingWindowException;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationQuotePolicy;
import kr.kro.airbob.domain.reservation.policy.ReservationStayPricePolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationQuoteRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReservationQuoteService {

	private final AccommodationRepository accommodationRepository;
	private final MemberRepository memberRepository;
	private final ReservationRepository reservationRepository;
	private final ReservationQuoteRepository quoteRepository;
	private final CouponUsageService couponUsageService;
	private final BookingWindowProvider bookingWindowProvider;
	private final Clock clock;
	private final ReservationQuotePolicy quotePolicy;

	@Transactional
	public ReservationResponse.Quote createQuote(ReservationRequest.Quote request, Long memberId) {
		memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE)
			.orElseThrow(MemberNotFoundException::new);

		Accommodation accommodation = accommodationRepository.findQuoteSnapshotByIdAndStatus(
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
		if (reservationRepository.existsConflictingReservationSnapshot(
			request.accommodationId(), request.checkInDate(), request.checkOutDate(), now)) {
			throw new ReservationConflictException();
		}

		ReservationStayPricePolicy.StayPrice stayPrice = ReservationStayPricePolicy.calculate(
			accommodation.getBasePrice(), request.checkInDate(), request.checkOutDate());
		long discountAmount = request.couponId() == null
			? 0L
			: couponUsageService.preview(
				memberId, request.couponId(), stayPrice.subtotal());
		String currency = accommodation.bookingCurrency();

		ReservationQuote quote = ReservationQuote.create(
			memberId,
			request,
			accommodation.getName(),
			currency,
			stayPrice,
			discountAmount,
			now,
			quotePolicy
		);
		quoteRepository.save(quote);
		return ReservationResponse.Quote.from(quote, now);
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
