package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ReservationHoldReleaseNotAllowedException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationPaymentAttemptNotAllowedException;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.policy.ReservationPaymentAttemptPolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReservationHoldCommandService {

	private static final String HOLD_RELEASE_REASON = "게스트가 결제 대기 예약 보유 해제";

	private final ReservationRepository reservationRepository;
	private final ReservationHistoryRepository historyRepository;
	private final CouponUsageService couponUsageService;
	private final ReservationPaymentAttemptPolicy paymentAttemptPolicy;
	private final ReservationInventoryService inventoryService;
	private final Clock clock;

	@Transactional
	public ReservationResponse.HoldRelease releaseHold(String reservationUid, Long memberId) {
		Reservation reservation = findGuestReservationForUpdate(reservationUid, memberId);
		Instant serverTime = clock.instant();

		if (reservation.getStatus() == ReservationStatus.EXPIRED) {
			return ReservationResponse.HoldRelease.from(reservation, false, serverTime);
		}
		if (reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
			throw new ReservationHoldReleaseNotAllowedException();
		}

		inventoryService.releaseHeldIfOwned(
			reservation.getAccommodation().getId(),
			reservation.getCheckInDate(),
			reservation.getCheckOutDate(),
			reservation.getId()
		);
		reservation.expire();
		couponUsageService.restore(reservation.getId());
		historyRepository.save(ReservationHistory.of(
			reservation, ChangeType.STATUS_CHANGE, HOLD_RELEASE_REASON));
		return ReservationResponse.HoldRelease.from(reservation, true, serverTime);
	}

	@Transactional
	public ReservationResponse.PaymentAttemptReady beginPaymentAttempt(
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

		return ReservationResponse.PaymentAttemptReady.from(
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
		return reservationRepository.findByReservationUidAndGuestIdWithLock(parsedUid, memberId)
			.orElseThrow(ReservationNotFoundException::new);
	}

	private void validateAttemptIssuanceState(Reservation reservation) {
		if (!reservation.isPaymentAttemptRequired()
			|| !reservation.requiresPayment()
			|| reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
			throw new ReservationPaymentAttemptNotAllowedException();
		}
	}
}
