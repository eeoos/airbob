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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.entity.PaymentTransactionType;
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
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationCheckoutIdempotencyConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationCheckInClosedException;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationOutsideBookingWindowException;
import kr.kro.airbob.domain.reservation.exception.ReservationOccupancyExceededException;
import kr.kro.airbob.domain.reservation.exception.ReservationStateChangeException;
import kr.kro.airbob.domain.reservation.idempotency.ReservationCheckoutEndpoint;
import kr.kro.airbob.domain.reservation.idempotency.ReservationCheckoutIdentity;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationHoldPolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationCheckoutRequestClaim;
import kr.kro.airbob.domain.reservation.repository.ReservationCheckoutRequestStore;
import kr.kro.airbob.domain.review.entity.ReviewStatus;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationTransactionService {

	private final AccommodationSearchRefreshPublisher searchRefreshPublisher;
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
	private final ReservationHoldPolicy holdPolicy;
	private final ReservationCheckoutRequestStore checkoutRequestStore;
	private final Clock clock;

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Reservation createPendingReservationInTx(
		ReservationRequest.Create request,
		Long memberId,
		String idempotencyKey,
		String reason
	) {
		ReservationCheckoutIdentity identity = ReservationCheckoutIdentity.from(
			idempotencyKey, request);
		Member guest = findActiveMember(memberId);
		ReservationCheckoutRequestClaim claim = checkoutRequestStore.lockOrCreate(
			memberId,
			ReservationCheckoutEndpoint.RESERVATION_CREATE_V1,
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

		Reservation reservation = createPendingReservation(request, reason, guest);
		checkoutRequestStore.complete(claim.id(), reservation.getId(), clock.instant());
		return reservation;
	}

	// The accommodation row is the inventory mutex; avoid empty-range gap locks here.
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Reservation createPendingReservationInTx(ReservationRequest.Create request, Long memberId, String reason) {
		return createPendingReservation(request, reason, findActiveMember(memberId));
	}

	private Reservation createPendingReservation(
		ReservationRequest.Create request,
		String reason,
		Member guest
	) {
		Accommodation accommodation = accommodationRepository.findByIdAndStatusForUpdate(
			request.accommodationId(), AccommodationStatus.PUBLISHED)
			.orElseThrow(AccommodationNotFoundException::new);
		validateOccupancy(accommodation, request.guestCount());
		if (!request.checkOutDate().isAfter(request.checkInDate())) {
			throw new InvalidReservationDateException();
		}
		BookingWindow bookingWindow = bookingWindowProvider.currentFor(accommodation.getTimeZoneId());
		if (!bookingWindow.containsStay(request.checkInDate(), request.checkOutDate())) {
			throw new ReservationOutsideBookingWindowException();
		}

		Instant now = clock.instant();
		Instant checkInAt = Reservation.resolveCheckInAt(accommodation, request.checkInDate());
		if (!now.isBefore(checkInAt)) {
			throw new ReservationCheckInClosedException();
		}

		if (reservationRepository.existsConflictingReservation(
			request.accommodationId(), request.checkInDate(), request.checkOutDate(), now)) {
			throw new ReservationConflictException();
		}

		String reservationCode = createReservationCode();

		Reservation reservation = Reservation.createPendingReservation(
			accommodation, guest, request, reservationCode, now, holdPolicy);
		reservationRepository.save(reservation);

		// 쿠폰 적용 (선택) — 같은 트랜잭션에서 사용 처리(중복 사용 방지) 후 결제 금액 차감
		if (request.couponId() != null) {
			long discount = couponUsageService.use(
				guest.getId(), request.couponId(), reservation.getId(), reservation.getTotalPrice());
			reservation.applyDiscount(discount);
		}
		if (!reservation.requiresPayment()) {
			reservation.confirmComplimentary();
		}

		historyRepository.save(ReservationHistory.of(reservation, ChangeType.CREATE, reason));

		if (!reservation.requiresPayment()) {
			requestSearchRefresh(reservation);
		}

		log.info("예약 ID {} (UID: {}) {} 상태로 DB 저장 완료",
			reservation.getId(), reservation.getReservationUid(), reservation.getStatus());
		return reservation;
	}

	private Member findActiveMember(Long memberId) {
		return memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE)
			.orElseThrow(MemberNotFoundException::new);
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

	@Transactional(readOnly = true)
	public ReservationResponse.GuestReservationInfos findMyReservations(Long memberId,
		CursorRequest.CursorPageRequest cursorRequest, ReservationFilterType filterType) {
		Instant now = clock.instant();

		Slice<Reservation> reservationSlice = reservationRepository.findMyReservationsByGuestIdWithCursor(
			memberId,
			cursorRequest.lastId(),
			cursorRequest.lastCreatedAt(),
			filterType,
			now,
			PageRequest.of(0, cursorRequest.size())
		);

		List<ReservationResponse.GuestReservationInfo> reservationInfos = reservationSlice.getContent().stream()
			.map(reservation -> ReservationResponse.GuestReservationInfo.from(reservation, now))
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
		return ReservationResponse.GuestDetail.from(
			reservation, paymentInfo, canWriteReview, clock.instant());
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
