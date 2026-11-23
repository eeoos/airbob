package kr.kro.airbob.core.domain.reservation;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.core.domain.accommodation.Accommodation;
import kr.kro.airbob.core.domain.accommodation.AccommodationNotFoundException;
import kr.kro.airbob.core.domain.accommodation.AccommodationReader;
import kr.kro.airbob.core.domain.accommodation.AccommodationStatus;
import kr.kro.airbob.core.domain.accommodation.OccupancyPolicy;
import kr.kro.airbob.core.domain.accommodation.port.AccommodationSearchRefreshRequester;
import kr.kro.airbob.core.domain.coupon.CouponUsageManager;
import kr.kro.airbob.core.domain.member.Member;
import kr.kro.airbob.core.domain.member.MemberReader;
import kr.kro.airbob.core.domain.reservation.idempotency.ReservationCheckoutIdentity;
import kr.kro.airbob.core.domain.reservation.idempotency.ReservationCheckoutRequestClaim;
import kr.kro.airbob.core.domain.reservation.inventory.MysqlNowaitFailureClassifier;
import kr.kro.airbob.core.domain.reservation.inventory.ReservationInventoryManager;
import kr.kro.airbob.core.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.core.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.core.domain.reservation.policy.ReservationHoldPolicy;
import kr.kro.airbob.core.domain.reservation.policy.ReservationStayPricePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationCheckoutService {

	private static final String CHECKOUT_REASON = "견적 기반 예약 생성";

	private final AccommodationSearchRefreshRequester searchRefreshPublisher;

	private final MemberReader memberReader;
	private final ReservationReader reservationReader;
	private final ReservationWriter reservationWriter;
	private final AccommodationReader accommodationReader;
	private final ReservationHistoryWriter reservationHistoryWriter;
	private final CouponUsageManager couponUsageManager;
	private final BookingWindowProvider bookingWindowProvider;
	private final ReservationHoldPolicy holdPolicy;
	private final ReservationQuoteReader quoteReader;
	private final ReservationInventoryManager inventoryManager;
	private final Clock clock;

	/**
	 * 견적을 체크아웃해 결제 대기 예약(0원이면 확정 예약)을 만든다. 같은 멱등 키의 끝난 요청은 그 예약을 다시 돌려준다.
	 * 상태와 결제 가능 여부는 커밋 직전 시각 기준이다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ReservationReady checkout(ReservationCheckout checkout, Long memberId, String idempotencyKey) {
		return ReservationReady.of(checkoutReservation(checkout, memberId, idempotencyKey), clock.instant());
	}

	private Reservation checkoutReservation(ReservationCheckout checkout, Long memberId, String idempotencyKey) {
		ReservationCheckoutIdentity identity = ReservationCheckoutIdentity.from(
			idempotencyKey, checkout);
		Member guest = findActiveMember(memberId);
		ReservationCheckoutRequestClaim claim = reservationWriter.claimCheckoutRequest(
			memberId,
			identity,
			clock.instant()
		);
		if (!claim.requestFingerprint().equals(identity.requestFingerprint())) {
			throw new ReservationCheckoutIdempotencyConflictException();
		}
		if (claim.reservationId() != null) {
			return reservationReader.readCheckoutReplay(claim.reservationId(), memberId);
		}

		ReservationQuote quote = quoteReader.readOwnedForUpdate(checkout.quoteUid(), memberId);
		if (quote.isCheckedOut()) {
			throw new ReservationQuoteAlreadyCheckedOutException();
		}

		ReservationCreateCommand createRequest = new ReservationCreateCommand(
			quote.getAccommodationId(),
			quote.getCheckInDate(),
			quote.getCheckOutDate(),
			quote.getGuestCount(),
			quote.getCouponId(),
			checkout.requestMessage()
		);
		Reservation reservation = createPendingReservation(createRequest, CHECKOUT_REASON, guest);
		Instant checkedOutAt = clock.instant();
		ReservationStayPricePolicy.StayPrice currentPrice = ReservationStayPricePolicy.calculate(
			reservation.getAccommodation().getBasePrice(),
			reservation.getCheckInDate(),
			reservation.getCheckOutDate()
		);
		if (!quote.matchesPricing(currentPrice, reservation.getDiscountAmount())
			|| !quote.getCurrency().equals(reservation.getCurrency())) {
			throw new ReservationQuoteStaleException();
		}

		quote.attachReservation(reservation.getId(), checkedOutAt);
		reservationWriter.completeCheckoutRequest(claim.id(), reservation.getId(), checkedOutAt);
		return reservation;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	Reservation createPendingReservationInTx(ReservationCreateCommand request, Long memberId, String reason) {
		return createPendingReservation(request, reason, findActiveMember(memberId));
	}

	private Reservation createPendingReservation(
		ReservationCreateCommand request,
		String reason,
		Member guest
	) {
		Accommodation accommodation = findBookingSnapshotNowait(request.accommodationId());
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
		if (!now.isBefore(checkInAt)) {
			throw new ReservationCheckInClosedException();
		}
		ReservationInventoryManager.LockedRange lockedInventory =
			inventoryManager.lockAvailableRangeNowait(
				request.accommodationId(),
				request.checkInDate(),
				request.checkOutDate(),
				now
			);

		String reservationCode = reservationWriter.newReservationCode();
		ReservationStayPricePolicy.StayPrice stayPrice = ReservationStayPricePolicy.calculate(
			accommodation.getBasePrice(), request.checkInDate(), request.checkOutDate());
		Reservation reservation = Reservation.createPendingReservation(
			accommodation,
			guest,
			request,
			reservationCode,
			now,
			holdPolicy,
			stayPrice,
			accommodation.bookingCurrency()
		);
		reservationWriter.create(reservation);

		// 쿠폰 적용 (선택) — 같은 트랜잭션에서 사용 처리(중복 사용 방지) 후 결제 금액 차감
		if (request.couponId() != null) {
			long discount = couponUsageManager.use(
				guest.getId(), request.couponId(), reservation.getId(), reservation.getTotalPrice());
			reservation.applyDiscount(discount);
		}
		if (!reservation.requiresPayment()) {
			reservation.confirmComplimentary();
			inventoryManager.claimLockedForBooked(lockedInventory, reservation.getId());
		} else {
			reservation.requirePaymentAttempt();
			inventoryManager.claimLockedForPending(
				lockedInventory, reservation.getId(), reservation.getExpiresAt());
		}

		reservationHistoryWriter.record(reservation, ChangeType.CREATE, reason);

		if (!reservation.requiresPayment()) {
			requestSearchRefresh(reservation);
		}

		log.info("예약 ID {} (UID: {}) {} 상태로 DB 저장 완료",
			reservation.getId(), reservation.getReservationUid(), reservation.getStatus());
		return reservation;
	}

	private Accommodation findBookingSnapshotNowait(Long accommodationId) {
		try {
			return accommodationReader.findBookingSnapshotForShare(
				accommodationId, AccommodationStatus.PUBLISHED)
				.orElseThrow(AccommodationNotFoundException::new);
		} catch (RuntimeException exception) {
			if (MysqlNowaitFailureClassifier.isNowait(exception)) {
				throw new ReservationInventoryBusyException(exception);
			}
			throw exception;
		}
	}

	private Member findActiveMember(Long memberId) {
		return memberReader.readActive(memberId);
	}

	private void validateOccupancy(Accommodation accommodation, int guestCount) {
		OccupancyPolicy occupancyPolicy = accommodation.getOccupancyPolicy();
		if (occupancyPolicy == null
			|| occupancyPolicy.getMaxOccupancy() == null
			|| guestCount > occupancyPolicy.getMaxOccupancy()) {
			throw new ReservationOccupancyExceededException();
		}
	}

	private void requestSearchRefresh(Reservation reservation) {
		searchRefreshPublisher.requestRefresh(
			reservation.getAccommodation().getAccommodationUid());
	}

}
