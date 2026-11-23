package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.core.domain.coupon.CouponUsageManager;
import kr.kro.airbob.domain.reservation.ReservationHoldRelease;
import kr.kro.airbob.domain.reservation.ReservationPaymentAttempt;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ReservationHoldReleaseNotAllowedException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationPaymentAttemptNotAllowedException;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryManager;
import kr.kro.airbob.domain.reservation.policy.ReservationPaymentAttemptPolicy;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReservationHoldCommandService {

	private static final String HOLD_RELEASE_REASON = "게스트가 결제 대기 예약 보유 해제";

	private final ReservationReader reservationReader;
	private final ReservationHistoryWriter reservationHistoryWriter;
	private final CouponUsageManager couponUsageManager;
	private final ReservationPaymentAttemptPolicy paymentAttemptPolicy;
	private final ReservationInventoryManager inventoryManager;
	private final Clock clock;

	@Transactional
	public ReservationHoldRelease releaseHold(String reservationUid, Long memberId) {
		Reservation reservation = findGuestReservationForUpdate(reservationUid, memberId);
		Instant serverTime = clock.instant();

		if (reservation.getStatus() == ReservationStatus.EXPIRED) {
			return ReservationHoldRelease.of(reservation, false, serverTime);
		}
		if (reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
			throw new ReservationHoldReleaseNotAllowedException();
		}

		inventoryManager.releaseHeldIfOwned(
			reservation.getAccommodation().getId(),
			reservation.getCheckInDate(),
			reservation.getCheckOutDate(),
			reservation.getId()
		);
		reservation.expire();
		couponUsageManager.restore(reservation.getId());
		reservationHistoryWriter.record(reservation, ChangeType.STATUS_CHANGE, HOLD_RELEASE_REASON);
		return ReservationHoldRelease.of(reservation, true, serverTime);
	}

	@Transactional
	public ReservationPaymentAttempt beginPaymentAttempt(
		String reservationUid,
		Long memberId
	) {
		Reservation reservation = findGuestReservationForUpdate(reservationUid, memberId);
		validateAttemptIssuanceState(reservation);
		Instant serverTime = clock.instant();

		if (reservation.getPaymentAttemptUid() == null) {
			paymentAttemptPolicy.validateFirstIssue(serverTime, reservation.getExpiresAt());
			reservation.issuePaymentAttempt(UUID.randomUUID(), serverTime);
		} else {
			if (reservation.getPaymentAttemptConsumedAt() != null) {
				throw new ReservationPaymentAttemptNotAllowedException();
			}
			paymentAttemptPolicy.validateReplay(serverTime, reservation.getExpiresAt());
		}

		return ReservationPaymentAttempt.of(
			reservation,
			paymentAttemptPolicy.remainingSeconds(serverTime, reservation.getExpiresAt()),
			serverTime
		);
	}

	private Reservation findGuestReservationForUpdate(String reservationUid, Long memberId) {
		UUID parsedUid;
		try {
			parsedUid = UUID.fromString(reservationUid);
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw new ReservationNotFoundException();
		}
		return reservationReader.readGuestOwnedForUpdate(parsedUid, memberId);
	}

	private void validateAttemptIssuanceState(Reservation reservation) {
		if (!reservation.isPaymentAttemptRequired()
			|| !reservation.requiresPayment()
			|| reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
			throw new ReservationPaymentAttemptNotAllowedException();
		}
	}
}
