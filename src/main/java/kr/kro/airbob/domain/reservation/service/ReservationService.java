package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;

import org.springframework.stereotype.Service;

import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Cancellation;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.service.PaymentCancellationCommandService;
import kr.kro.airbob.domain.reservation.admission.ReservationCheckoutAdmission;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReservationService {

	private final ReservationTransactionService transactionService;
	private final PaymentCancellationCommandService cancellationCommandService;
	private final ReservationCheckoutAdmission checkoutAdmission;
	private final Clock clock;

	public ReservationResponse.Ready createPendingReservation(
		ReservationRequest.Create request,
		Long memberId,
		String idempotencyKey
	) {
		if (!request.checkOutDate().isAfter(request.checkInDate())) {
			throw new InvalidReservationDateException();
		}

		Reservation reservation = checkoutAdmission.execute(() -> idempotencyKey == null
			? transactionService.createPendingReservationInTx(request, memberId, "사용자 예약 생성")
			: transactionService.createPendingReservationInTx(
				request,
				memberId,
				idempotencyKey,
				"사용자 예약 생성"
			));
		return ReservationResponse.Ready.from(reservation, clock.instant());
	}

	public ReservationResponse.Ready createPendingReservation(
		ReservationRequest.Checkout request,
		Long memberId,
		String idempotencyKey
	) {
		Reservation reservation = checkoutAdmission.execute(() ->
			transactionService.createPendingReservationInTx(
				request,
				memberId,
				idempotencyKey,
				"견적 기반 예약 생성"
			)
		);
		return ReservationResponse.Ready.from(reservation, clock.instant());
	}

	public Cancellation cancelReservation(
		String reservationUid,
		PaymentRequest.Cancel request,
		Long memberId
	) {
		return cancellationCommandService.requestCancellation(reservationUid, request, memberId);
	}

	public ReservationResponse.GuestReservationInfos findMyReservations(Long memberId, CursorRequest.CursorPageRequest cursorRequest, ReservationFilterType filterType) {
		return transactionService.findMyReservations(memberId, cursorRequest, filterType);
	}

	public ReservationResponse.GuestDetail findMyReservationDetail(String reservationUidStr, Long memberId) {
		return transactionService.findMyReservationDetail(reservationUidStr, memberId);
	}

	public ReservationResponse.HostReservationInfos findHostReservations(Long hostId, CursorRequest.CursorPageRequest cursorRequest, ReservationFilterType filterType) {
		return transactionService.findHostReservations(hostId, cursorRequest, filterType);
	}

	public ReservationResponse.HostDetail findHostReservationDetail(String reservationUid, Long hostId) {
		return transactionService.findHostReservationDetail(reservationUid, hostId);
	}
}
