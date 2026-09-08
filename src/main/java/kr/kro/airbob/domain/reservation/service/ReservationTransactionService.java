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
import kr.kro.airbob.domain.reservation.command.ReservationCreateCommand;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.repository.projection.GuestReservationListProjection;
import kr.kro.airbob.domain.reservation.repository.projection.GuestReservationDetailProjection;
import kr.kro.airbob.domain.reservation.repository.projection.HostReservationListProjection;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationQuote;
import kr.kro.airbob.domain.reservation.exception.ReservationCheckoutIdempotencyConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationCheckInClosedException;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationOutsideBookingWindowException;
import kr.kro.airbob.domain.reservation.exception.ReservationOccupancyExceededException;
import kr.kro.airbob.domain.reservation.exception.ReservationQuoteAlreadyCheckedOutException;
import kr.kro.airbob.domain.reservation.exception.ReservationQuoteExpiredException;
import kr.kro.airbob.domain.reservation.exception.ReservationQuoteNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationQuoteStaleException;
import kr.kro.airbob.domain.reservation.exception.ReservationInventoryBusyException;
import kr.kro.airbob.domain.reservation.exception.ReservationStateChangeException;
import kr.kro.airbob.domain.reservation.idempotency.ReservationCheckoutIdentity;
import kr.kro.airbob.domain.reservation.inventory.MysqlNowaitFailureClassifier;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationHoldPolicy;
import kr.kro.airbob.domain.reservation.policy.ReservationStayPricePolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationQuoteRepository;
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
	private final ReservationRepository reservationRepository;
	private final AccommodationRepository accommodationRepository;
	private final ReservationHistoryRepository historyRepository;
	private final CouponUsageService couponUsageService;
	private final BookingWindowProvider bookingWindowProvider;
	private final ReservationHoldPolicy holdPolicy;
	private final ReservationQuoteRepository quoteRepository;
	private final ReservationCheckoutRequestStore checkoutRequestStore;
	private final ReservationInventoryService inventoryService;
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

		Instant quoteCheckedAt = clock.instant();
		if (quote.isExpiredAt(quoteCheckedAt)) {
			throw new ReservationQuoteExpiredException();
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
		if (quote.isExpiredAt(checkedOutAt)) {
			throw new ReservationQuoteExpiredException();
		}
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
		ReservationInventoryService.LockedRange lockedInventory =
			inventoryService.lockAvailableRangeNowait(
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
			long discount = couponUsageService.use(
				guest.getId(), request.couponId(), reservation.getId(), reservation.getTotalPrice());
			reservation.applyDiscount(discount);
		}
		if (!reservation.requiresPayment()) {
			reservation.confirmComplimentary();
			inventoryService.claimLockedForBooked(lockedInventory, reservation.getId());
		} else {
			reservation.requirePaymentAttempt();
			inventoryService.claimLockedForPending(
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
			return accommodationRepository.findBookingSnapshotForShare(
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

		Slice<GuestReservationListProjection> reservationSlice = reservationRepository.findMyReservationsByGuestIdWithCursor(
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
			GuestReservationListProjection::id,
			GuestReservationListProjection::createdAt
		);

		return ReservationResponse.GuestReservationInfos.from(reservationInfos, pageInfo);
	}

	@Transactional(readOnly = true)
	public ReservationResponse.GuestDetail findMyReservationDetail(String reservationUidStr, Long memberId) {
		UUID reservationUid = UUID.fromString(reservationUidStr);

		GuestReservationDetailProjection reservation = reservationRepository.findReservationDetailByUidAndGuestId(reservationUid, memberId)
			.orElseThrow(ReservationNotFoundException::new);

		Instant serverTime = clock.instant();
		boolean canWriteReview = canWriteReview(memberId, reservation, serverTime);

		return ReservationResponse.GuestDetail.from(
			reservation, canWriteReview, serverTime);
	}

	@Transactional(readOnly = true)
	public ReservationResponse.HostReservationInfos findHostReservations(Long hostId, CursorRequest.CursorPageRequest cursorRequest, ReservationFilterType filterType) {
		Slice<HostReservationListProjection> reservationSlice = reservationRepository.findHostReservationsByHostIdWithCursor(
			hostId,
			cursorRequest.lastId(),
			cursorRequest.lastCreatedAt(),
			filterType,
			clock.instant(),
			PageRequest.of(0, cursorRequest.size())
		);

		List<HostReservationListProjection> reservations = reservationSlice.getContent();

		List<ReservationResponse.HostReservationInfo> reservationInfos = reservations.stream()
			.map(ReservationResponse.HostReservationInfo::from).collect(Collectors.toList());

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			reservations,
			reservationSlice.hasNext(),
			HostReservationListProjection::id,
			HostReservationListProjection::createdAt
		);

		return ReservationResponse.HostReservationInfos.from(reservationInfos, pageInfo);
	}

	@Transactional(readOnly = true)
	public ReservationResponse.HostDetail findHostReservationDetail(String reservationUidStr, Long hostId) {
		UUID reservationUid = UUID.fromString(reservationUidStr);
		return reservationRepository.findHostReservationDetailByUidAndHostId(reservationUid, hostId)
			.map(ReservationResponse.HostDetail::from)
			.orElseThrow(ReservationNotFoundException::new);
	}

	private boolean canWriteReview(Long memberId, GuestReservationDetailProjection reservation, Instant serverTime) {
		if (reservation.accommodationStatus() != AccommodationStatus.PUBLISHED
			|| !reservation.status().isReviewableReservation()
			|| reservation.checkOutAt().isAfter(serverTime)) {
			return false;
		}
		return !reviewRepository.existsByAccommodationIdAndAuthorIdAndStatus(
			reservation.accommodationId(), memberId, ReviewStatus.PUBLISHED);
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
