package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.entity.PaymentTransactionType;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationAccessDeniedException;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationOutsideBookingWindowException;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.review.entity.ReviewStatus;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.search.event.AccommodationIndexingEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationTransactionService {

	private final OutboxEventPublisher outboxEventPublisher;
	private final CursorPageInfoCreator cursorPageInfoCreator;

	private final MemberRepository memberRepository;
	private final ReviewRepository reviewRepository;
	private final PaymentRepository paymentRepository;
	private final ReservationRepository reservationRepository;
	private final AccommodationRepository accommodationRepository;
	private final PaymentTransactionRepository paymentTransactionRepository;
	private final ReservationHistoryRepository historyRepository;
	private final CouponUsageService couponUsageService;
	private final BookingWindowProvider bookingWindowProvider;
	private final Clock clock;

	@Transactional
	public Reservation createPendingReservationInTx(ReservationRequest.Create request, Long memberId, String reason) {
		Member guest = memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE).orElseThrow(MemberNotFoundException::new);
		Accommodation accommodation = accommodationRepository.findByIdAndStatusForUpdate(
			request.accommodationId(), AccommodationStatus.PUBLISHED)
			.orElseThrow(AccommodationNotFoundException::new);
		if (!request.checkOutDate().isAfter(request.checkInDate())) {
			throw new InvalidReservationDateException();
		}
		BookingWindow bookingWindow = bookingWindowProvider.currentFor(accommodation.getTimeZoneId());
		if (!bookingWindow.containsStay(request.checkInDate(), request.checkOutDate())) {
			throw new ReservationOutsideBookingWindowException();
		}

		Instant now = clock.instant();

		if (reservationRepository.existsConflictingReservation(
			request.accommodationId(), request.checkInDate(), request.checkOutDate(), now)) {
			throw new ReservationConflictException();
		}

		String reservationCode = createReservationCode();

		Reservation pendingReservation = Reservation.createPendingReservation(
			accommodation, guest, request, reservationCode, now);
		reservationRepository.save(pendingReservation);

		// 쿠폰 적용 (선택) — 같은 트랜잭션에서 사용 처리(중복 사용 방지) 후 결제 금액 차감
		if (request.couponId() != null) {
			long discount = couponUsageService.use(
				memberId, request.couponId(), pendingReservation.getId(), pendingReservation.getTotalPrice());
			pendingReservation.applyDiscount(discount);
		}

		historyRepository.save(ReservationHistory.of(pendingReservation, ChangeType.CREATE, reason));

		// SAGA 시작 이벤트 발행
		outboxEventPublisher.save(
			EventType.RESERVATION_PENDING,
			new ReservationEvent.ReservationPendingEvent(
				pendingReservation.getTotalPrice(),
				null, // 이 시점에는 paymentKey X
				pendingReservation.getReservationUid().toString()
			)
		);

		log.info("예약 ID {} (UID: {}) PENDING 상태로 DB 저장 완료", pendingReservation.getId(), pendingReservation.getReservationUid());
		return pendingReservation;
	}

	@Transactional
	public void cancelReservationInTx(String reservationUid, PaymentRequest.Cancel request, Long memberId) {
		Reservation reservation = reservationRepository.findByReservationUidWithLock(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		// todo: 추가 쿼리 발생 -> member까지 같이 조회 필요
		if (!reservation.getGuest().getId().equals(memberId)) {
			throw new ReservationAccessDeniedException();
		}

		if (reservation.getStatus() == ReservationStatus.CANCELLATION_PENDING) {
			log.info("[예약 취소 요청-SKIP] 이미 취소 처리 중인 예약입니다. UID: {}", reservationUid);
			return;
		}

		validateFullCancellationAmount(reservation.getReservationUid(), request.cancelAmount());
		reservation.requestCancellation();

		historyRepository.save(ReservationHistory.of(
			reservation, ChangeType.STATUS_CHANGE, request.cancelReason()));

		outboxEventPublisher.save(
			EventType.RESERVATION_CANCELLATION_REQUESTED,
			new ReservationEvent.ReservationCancellationRequestedEvent(
				reservationUid,
				request.cancelReason(),
				request.cancelAmount()
			)
		);
		log.info("[예약 취소 요청]: Reservation UID {} 상태 CANCELLATION_PENDING 변경 및 이벤트 발행 완료", reservationUid);
	}

	@Transactional
	public void completeCancellationInTx(String reservationUid) {
		UUID reservationUuid = UUID.fromString(reservationUid);
		Reservation reservation = reservationRepository.findByReservationUidWithLock(reservationUuid)
			.orElseThrow(ReservationNotFoundException::new);

		Payment payment = paymentRepository.findByReservationReservationUidWithLock(reservationUuid)
			.orElseThrow(PaymentNotFoundException::new);
		validateFullyCancelledPayment(payment);

		if (reservation.getStatus() == ReservationStatus.CANCELLED) {
			publishReservationChanged(reservation);
			log.info("[예약 취소 성공-SKIP] 이미 취소 완료된 예약입니다. ES 갱신 이벤트를 재발행합니다. UID: {}",
				reservationUid);
			return;
		}
		reservation.completeCancellation();

		couponUsageService.restore(reservation.getId());
		historyRepository.save(ReservationHistory.ofSystem(
			reservation, ChangeType.CANCEL, "PG 결제 취소 성공", "KAFKA"));
		publishReservationChanged(reservation);
		log.info("[예약 취소 성공] 예약 상태 CANCELLED 확정 완료. UID: {}", reservationUid);
	}

	private void validateFullyCancelledPayment(Payment payment) {
		if (payment.getStatus() != PaymentStatus.CANCELED
			|| !Long.valueOf(0L).equals(payment.getBalanceAmount())) {
			throw new IllegalStateException("전액 환불이 확인되지 않은 예약은 취소 완료할 수 없습니다.");
		}
	}

	private void publishReservationChanged(Reservation reservation) {
		outboxEventPublisher.save(
			EventType.RESERVATION_CHANGED,
			new AccommodationIndexingEvents.ReservationChangedEvent(
				reservation.getAccommodation().getAccommodationUid().toString()
			)
		);
	}

	@Transactional
	public void revertCancellationInTx(String reservationUid, String reason) {
		UUID reservationUuid = UUID.fromString(reservationUid);
		Reservation reservation = reservationRepository.findByReservationUidWithLock(reservationUuid)
			.orElseThrow(ReservationNotFoundException::new);

		if (reservation.getStatus() == ReservationStatus.CANCELLATION_FAILED) {
			log.info("[취소 실패-SKIP] 이미 취소 실패가 확정된 예약입니다. UID: {}", reservationUid);
			return;
		}
		if (reservation.getStatus() == ReservationStatus.CANCELLED) {
			recoverLegacyCancellationFailure(reservation, reservationUuid, reason);
			return;
		}
		reservation.failCancellation();

		recordCancellationFailure(reservation, reason);
	}

	private void recoverLegacyCancellationFailure(
		Reservation reservation,
		UUID reservationUid,
		String reason
	) {
		Payment payment = paymentRepository.findByReservationReservationUidWithLock(reservationUid)
			.orElseThrow(PaymentNotFoundException::new);
		if (payment.getStatus() == PaymentStatus.CANCELED
			&& Long.valueOf(0L).equals(payment.getBalanceAmount())) {
			log.info("[레거시 취소 실패-SKIP] 전액 환불이 이미 확정된 예약입니다. UID: {}", reservationUid);
			return;
		}
		boolean paymentStillActive = payment.getBalanceAmount() != null
			&& payment.getBalanceAmount() > 0L
			&& (payment.getStatus() == PaymentStatus.DONE
				|| payment.getStatus() == PaymentStatus.PARTIAL_CANCELED);
		if (!paymentStillActive) {
			throw new IllegalStateException("레거시 예약 취소 실패의 결제 상태가 일관되지 않습니다.");
		}

		reservation.recoverLegacyCancellationFailure();
		couponUsageService.reuse(reservation.getId());
		recordCancellationFailure(reservation, reason);
	}

	private void recordCancellationFailure(Reservation reservation, String reason) {

		historyRepository.save(ReservationHistory.ofSystem(reservation, ChangeType.STATUS_CHANGE,
			"PG 결제 취소 실패: " + reason, "KAFKA"));

		log.info("[예약 취소 실패] 예약 상태 CANCELLATION_FAILED로 변경. UID: {}",
			reservation.getReservationUid());
	}

	private void validateFullCancellationAmount(UUID reservationUid, Long cancelAmount) {
		if (cancelAmount == null) {
			return;
		}
		Payment payment = paymentRepository.findByReservationReservationUid(reservationUid)
			.orElseThrow(PaymentNotFoundException::new);
		if (!cancelAmount.equals(payment.getBalanceAmount())) {
			throw new InvalidInputException("예약 취소는 현재 결제 잔액 전액만 가능합니다.");
		}
	}

	@Transactional(readOnly = true)
	public ReservationResponse.GuestReservationInfos findMyReservations(Long memberId,
		CursorRequest.CursorPageRequest cursorRequest, ReservationFilterType filterType) {

		Slice<Reservation> reservationSlice = reservationRepository.findMyReservationsByGuestIdWithCursor(
			memberId,
			cursorRequest.lastId(),
			cursorRequest.lastCreatedAt(),
			filterType,
			clock.instant(),
			PageRequest.of(0, cursorRequest.size())
		);

		List<ReservationResponse.GuestReservationInfo> reservationInfos = reservationSlice.getContent().stream()
			.map(ReservationResponse.GuestReservationInfo::from)
			.collect(Collectors.toList());

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			reservationSlice.getContent(),
			reservationSlice.hasNext(),
			Reservation::getId,
			Reservation::getCreatedAt
		);

		return ReservationResponse.GuestReservationInfos.from(reservationInfos, pageInfo);
	}

	@Transactional(readOnly = true)
	public ReservationResponse.GuestDetail findMyReservationDetail(String reservationUidStr, Long memberId) {
		UUID reservationUid = UUID.fromString(reservationUidStr);

		Reservation reservation = reservationRepository.findReservationDetailByUidAndGuestId(reservationUid, memberId)
			.orElseThrow(ReservationNotFoundException::new);

		Payment payment = findPaymentByReservationUidNullable(reservationUid);
		PaymentResponse.PaymentInfo paymentInfo = getPaymentInfo(reservationUidStr, payment,
			reservation);

		boolean canWriteReview = isCanWriteReview(memberId, reservation);

		// mapstruct 적용
		return ReservationResponse.GuestDetail.from(reservation, paymentInfo, canWriteReview);
	}

	@Transactional(readOnly = true)
	public ReservationResponse.HostReservationInfos findHostReservations(Long hostId, CursorRequest.CursorPageRequest cursorRequest, ReservationFilterType filterType) {
		Slice<Reservation> reservationSlice = reservationRepository.findHostReservationsByHostIdWithCursor(
			hostId,
			cursorRequest.lastId(),
			cursorRequest.lastCreatedAt(),
			filterType,
			clock.instant(),
			PageRequest.of(0, cursorRequest.size())
		);

		List<Reservation> reservations = reservationSlice.getContent();

		List<ReservationResponse.HostReservationInfo> reservationInfos = reservations.stream()
			.map(ReservationResponse.HostReservationInfo::from).collect(Collectors.toList());

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			reservations,
			reservationSlice.hasNext(),
			Reservation::getId,
			Reservation::getCreatedAt
		);

		return ReservationResponse.HostReservationInfos.from(reservationInfos, pageInfo);
	}

	@Transactional(readOnly = true)
	public ReservationResponse.HostDetail findHostReservationDetail(String reservationUidStr, Long hostId) {

		UUID reservationUid = UUID.fromString(reservationUidStr);

		Reservation reservation = reservationRepository.findHostReservationDetailByUidAndHostId(reservationUid, hostId)
			.orElseThrow(ReservationNotFoundException::new);

		Payment payment = findPaymentByReservationUidNullable(reservationUid);
		PaymentResponse.PaymentInfo paymentInfo = (payment != null)
			? PaymentResponse.PaymentInfo.from(payment, findCancelTransactions(payment)) : null;

		return ReservationResponse.HostDetail.from(reservation, paymentInfo);
	}

	private PaymentResponse.PaymentInfo getPaymentInfo(String reservationUidStr, Payment payment,
		Reservation reservation) {
		PaymentResponse.PaymentInfo paymentInfo = null;

		if (payment != null) { // 결제 완료된 예약
			paymentInfo = PaymentResponse.PaymentInfo.from(payment, findCancelTransactions(payment));
		} else if (reservation.getStatus() == ReservationStatus.PAYMENT_PENDING
			|| reservation.getStatus() == ReservationStatus.PAYMENT_PROCESSING) { // 결제 대기중인 예약(가상계좌)
			paymentInfo = paymentTransactionRepository
				.findByOrderIdOrderByCreatedAtDesc(reservationUidStr)
				.stream()
				.filter(tx -> tx.getTransactionType() == PaymentTransactionType.VIRTUAL_ISSUED)
				.findFirst()
				.map(PaymentResponse.PaymentInfo::from)
				.orElse(null);
		}
		return paymentInfo;
	}

	private java.util.List<PaymentTransaction> findCancelTransactions(Payment payment) {
		return paymentTransactionRepository.findByPaymentIdAndTransactionTypeInOrderByCreatedAtAsc(
			payment.getId(), java.util.List.of(PaymentTransactionType.CANCEL, PaymentTransactionType.PARTIAL_CANCEL));
	}

	private boolean isCanWriteReview(Long memberId, Reservation reservation) {
		boolean canWriteReview = false;
		if (reservation.getStatus().isReviewableReservation() &&
			!reservation.getCheckOutAt().isAfter(clock.instant())) {

			// 아직 작성한 리뷰가 없는지 확인
			canWriteReview = !reviewRepository.existsByAccommodationIdAndAuthorIdAndStatus(
				reservation.getAccommodation().getId(),
				memberId,
				ReviewStatus.PUBLISHED
			);
		}
		return canWriteReview;
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

	private Payment findPaymentByReservationUidNullable(UUID reservationUid) {
		return paymentRepository.findByReservationReservationUid(reservationUid)
			.orElse(null);
	}
}
