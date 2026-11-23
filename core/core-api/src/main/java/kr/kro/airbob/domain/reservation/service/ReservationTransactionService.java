package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;
import java.time.Instant;

import org.apache.commons.lang3.RandomStringUtils;
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
import kr.kro.airbob.domain.reservation.command.ReservationCreateCommand;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationQuote;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import kr.kro.airbob.domain.reservation.exception.ReservationCheckInClosedException;
import kr.kro.airbob.domain.reservation.exception.ReservationCheckoutIdempotencyConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationInventoryBusyException;
import kr.kro.airbob.domain.reservation.exception.ReservationOccupancyExceededException;
import kr.kro.airbob.domain.reservation.exception.ReservationOutsideBookingWindowException;
import kr.kro.airbob.domain.reservation.exception.ReservationQuoteAlreadyCheckedOutException;
import kr.kro.airbob.domain.reservation.exception.ReservationQuoteNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationQuoteStaleException;
import kr.kro.airbob.domain.reservation.exception.ReservationStateChangeException;
import kr.kro.airbob.domain.reservation.idempotency.ReservationCheckoutIdentity;
import kr.kro.airbob.domain.reservation.inventory.MysqlNowaitFailureClassifier;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryManager;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationHoldPolicy;
import kr.kro.airbob.domain.reservation.policy.ReservationStayPricePolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationCheckoutRequestClaim;
import kr.kro.airbob.domain.reservation.repository.ReservationCheckoutRequestStore;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationQuoteRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationTransactionService {

	private final AccommodationSearchRefreshRequester searchRefreshPublisher;

	private final MemberReader memberReader;
	private final ReservationRepository reservationRepository;
	private final AccommodationReader accommodationReader;
	private final ReservationHistoryRepository historyRepository;
	private final CouponUsageManager couponUsageManager;
	private final BookingWindowProvider bookingWindowProvider;
	private final ReservationHoldPolicy holdPolicy;
	private final ReservationQuoteRepository quoteRepository;
	private final ReservationCheckoutRequestStore checkoutRequestStore;
	private final ReservationInventoryManager inventoryManager;
	private final Clock clock;

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Reservation createPendingReservationInTx(
		ReservationRequest.Checkout request,
		Long memberId,
		String idempotencyKey,
		String reason
	) {
		ReservationCheckoutIdentity identity = ReservationCheckoutIdentity.from(
			idempotencyKey, request);
		Member guest = findActiveMember(memberId);
		ReservationCheckoutRequestClaim claim = checkoutRequestStore.lockOrCreate(
			memberId,
			identity,
			clock.instant()
		);
		if (!claim.requestFingerprint().equals(identity.requestFingerprint())) {
			throw new ReservationCheckoutIdempotencyConflictException();
		}
		if (claim.reservationId() != null) {
			return reservationRepository.findCheckoutReplayByIdAndGuestId(
				claim.reservationId(), memberId)
				.orElseThrow(ReservationStateChangeException::new);
		}

		ReservationQuote quote = quoteRepository.findByQuoteUidAndMemberIdForUpdate(
			request.quoteUid(), memberId)
			.orElseThrow(ReservationQuoteNotFoundException::new);
		if (quote.isCheckedOut()) {
			throw new ReservationQuoteAlreadyCheckedOutException();
		}

		ReservationCreateCommand createRequest = new ReservationCreateCommand(
			quote.getAccommodationId(),
			quote.getCheckInDate(),
			quote.getCheckOutDate(),
			quote.getGuestCount(),
			quote.getCouponId(),
			request.requestMessage()
		);
		Reservation reservation = createPendingReservation(createRequest, reason, guest);
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
		checkoutRequestStore.complete(claim.id(), reservation.getId(), checkedOutAt);
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

		String reservationCode = createReservationCode();
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
		reservationRepository.saveAndFlush(reservation);

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

		historyRepository.save(ReservationHistory.of(reservation, ChangeType.CREATE, reason));

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

	private String createReservationCode() {
		String reservationCode;
		do {
			reservationCode = generateReservationCode();
		} while (reservationRepository.existsByReservationCode(reservationCode));

		return reservationCode;
	}

	private String generateReservationCode() {
		return RandomStringUtils.randomAlphanumeric(6).toUpperCase();
	}

}
