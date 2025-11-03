package kr.kro.airbob.domain.reservation.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.entity.ReservationStatusHistory;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationAccessDeniedException;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationStatusHistoryRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.search.event.AccommodationIndexingEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationTransactionService {

	private static final String KAFKA_CONSUMER = "SYSTEM:KAFKA_CONSUMER";

	private final OutboxEventPublisher outboxEventPublisher;
	private final CursorPageInfoCreator cursorPageInfoCreator;

	private final MemberRepository memberRepository;
	private final PaymentRepository paymentRepository;
	private final ReservationRepository reservationRepository;
	private final AccommodationRepository accommodationRepository;
	private final ReservationStatusHistoryRepository historyRepository;

	@Transactional
	public Reservation createPendingReservationInTx(ReservationRequest.Create request, Long memberId, String reason) {
		String changedBy = "USER_ID:" + memberId;

		Member guest = memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE).orElseThrow(MemberNotFoundException::new);
		Accommodation accommodation = accommodationRepository.findByIdAndStatus(request.accommodationId(), AccommodationStatus.PUBLISHED)
			.orElseThrow(AccommodationNotFoundException::new);

		LocalDateTime checkInDateTime = request.checkInDate().atTime(accommodation.getCheckInTime());
		LocalDateTime checkOutDateTime = request.checkOutDate().atTime(accommodation.getCheckOutTime());

		if (reservationRepository.existsConflictingReservation(request.accommodationId(), checkInDateTime, checkOutDateTime)) {
			throw new ReservationConflictException();
		}

		Reservation pendingReservation = Reservation.createPendingReservation(accommodation, guest, request);
		reservationRepository.save(pendingReservation);

		historyRepository.save(ReservationStatusHistory.builder()
			.reservation(pendingReservation)
			.previousStatus(null)
			.newStatus(ReservationStatus.PAYMENT_PENDING)
			.changedBy(changedBy)
			.reason(reason)
			.build());

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
		String changedBy = "USER_ID:" + memberId;

		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		// todo: 추가 쿼리 발생 -> member까지 같이 조회 필요
		if (!reservation.getGuest().getId().equals(memberId)) {
			throw new ReservationAccessDeniedException();
		}

		ReservationStatus previousStatus = reservation.getStatus();
		reservation.cancel();

		historyRepository.save(ReservationStatusHistory.builder()
			.reservation(reservation)
			.previousStatus(previousStatus)
			.newStatus(ReservationStatus.CANCELLED)
			.changedBy(changedBy)
			.reason(request.cancelReason())
			.build());

		outboxEventPublisher.save(
			EventType.RESERVATION_CANCELLED,
			new ReservationEvent.ReservationCancelledEvent(
				reservationUid,
				request.cancelReason(),
				request.cancelAmount()
			)
		);
		log.info("[예약 취소 완료]: Reservation UID {} 상태 변경 및 이벤트 발행 완료", reservationUid);
	}

	@Transactional
	public void confirmReservationInTx(String reservationUid) {

		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
			log.info("[결제 성공 확인] 이미 확정 처리된 예약: {}", reservation.getId());
			return;
		}

		String confirmationCode = createConfirmationCode();

		ReservationStatus previousStatus = reservation.getStatus();
		reservation.confirm(confirmationCode);

		historyRepository.save(ReservationStatusHistory.builder()
			.reservation(reservation)
			.previousStatus(previousStatus)
			.newStatus(ReservationStatus.CONFIRMED)
			.changedBy(KAFKA_CONSUMER)
			.reason("결제 성공")
			.build());

		outboxEventPublisher.save(
			EventType.RESERVATION_CONFIRMED,
			new ReservationEvent.ReservationConfirmedEvent(
				reservation.getAccommodation().getId(),
				reservation.getCheckIn().toLocalDate(),
				reservation.getCheckOut().toLocalDate()
			)
		);

		// es 색인
		outboxEventPublisher.save(
			EventType.RESERVATION_CHANGED,
			new AccommodationIndexingEvents.ReservationChangedEvent(
				reservation.getAccommodation().getAccommodationUid().toString()
			)
		);

		log.info("[결제 성공 확인]: 예약 ID {} 상태 변경(CONFIRMED) 및 이벤트 발행 완료", reservation.getId());
	}

	@Transactional
	public void expireReservationInTx(String reservationUid, String reason) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		if (reservation.getStatus() == ReservationStatus.EXPIRED) {
			log.info("[결제 실패 확인] 이미 만료 처리된 예약: {}", reservation.getId());
			return;
		}

		ReservationStatus previousStatus = reservation.getStatus();
		reservation.expire();

		historyRepository.save(ReservationStatusHistory.builder()
			.reservation(reservation)
			.previousStatus(previousStatus)
			.newStatus(ReservationStatus.EXPIRED)
			.changedBy(KAFKA_CONSUMER)
			.reason(reason)
			.build());

		outboxEventPublisher.save(
			EventType.RESERVATION_EXPIRED,
			new ReservationEvent.ReservationExpiredEvent(
				reservation.getAccommodation().getId(),
				reservation.getCheckIn().toLocalDate(),
				reservation.getCheckOut().toLocalDate()
			)
		);

		log.info("[결제 실패 확인] 예약 ID {} 상태 변경(EXPIRED) 사유: {}", reservation.getId(), reason);
	}

	@Transactional
	public void revertCancellationInTx(String reservationUid, String reason) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		// 이미 CANCELLATION_FAILED 상태라면 중복 처리 방지
		if (reservation.getStatus() == ReservationStatus.CANCELLATION_FAILED) {
			log.warn("[보상-SKIP] 이미 취소 실패 처리된 예약입니다. UID: {}", reservationUid);
			return;
		}

		ReservationStatus previousStatus = reservation.getStatus();
		reservation.failCancellation();

		historyRepository.save(ReservationStatusHistory.builder()
			.reservation(reservation)
			.previousStatus(previousStatus)
			.newStatus(ReservationStatus.CANCELLATION_FAILED)
			.changedBy(KAFKA_CONSUMER)
			.reason("결제 취소 실패 보상 트랜잭션: " + reason)
			.build()
		);

		log.info("[보상-SUCCESS] 예약 취소 실패 보상 처리 완료. 예약 상태 CANCELLATION_FAILED로 변경. UID: {}", reservationUid);
	}

	@Transactional(readOnly = true)
	public ReservationResponse.MyReservationInfos findMyReservations(Long memberId,
		CursorRequest.CursorPageRequest cursorRequest) {

		Slice<Reservation> reservationSlice = reservationRepository.findMyReservationsByGuestIdWithCursor(
			memberId,
			cursorRequest.lastId(),
			cursorRequest.lastCreatedAt(),
			PageRequest.of(0, cursorRequest.size())
		);

		List<ReservationResponse.MyReservationInfo> reservationInfos = reservationSlice.getContent().stream()
			.map(ReservationResponse.MyReservationInfo::from)
			.collect(Collectors.toList());

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			reservationSlice.getContent(),
			reservationSlice.hasNext(),
			Reservation::getId,
			Reservation::getCreatedAt
		);

		return ReservationResponse.MyReservationInfos.builder()
			.reservations(reservationInfos)
			.pageInfo(pageInfo)
			.build();
	}

	@Transactional(readOnly = true)
	public ReservationResponse.DetailInfo findMyReservationDetail(String reservationUidStr, Long memberId) {
		UUID reservationUid = UUID.fromString(reservationUidStr);

		Reservation reservation = reservationRepository.findReservationDetailByUidAndGuestId(reservationUid, memberId)
			.orElseThrow(ReservationNotFoundException::new);

		Payment payment = findPaymentByReservationUidNullable(reservationUid);

		Accommodation accommodation = reservation.getAccommodation();
		Address address = accommodation.getAddress();
		Member host = accommodation.getMember();

		ReservationResponse.AccommodationAddressInfo addressInfo = ReservationResponse.AccommodationAddressInfo.builder()
			.country(address.getCountry())
			.city(address.getCity())
			.district(address.getDistrict())
			.street(address.getStreet())
			.detail(address.getDetail())
			.postalCode(address.getPostalCode())
			.fullAddress(buildFullAddress(address))
			.latitude(address.getLatitude())
			.longitude(address.getLongitude())
			.build();

		ReservationResponse.AccommodationHostInfo hostInfo = ReservationResponse.AccommodationHostInfo.builder()
			.id(host.getId())
			.nickname(host.getNickname())
			.build();

		// mapstruct 적용
		return ReservationResponse.DetailInfo.builder()
			.reservationUid(reservation.getReservationUid().toString())
			.status(reservation.getStatus())
			.createdAt(reservation.getCreatedAt())
			.guestCount(reservation.getGuestCount())
			.accommodationId(accommodation.getId())
			.accommodationName(accommodation.getName())
			.accommodationThumbnailUrl(accommodation.getThumbnailUrl())
			.accommodationAddress(addressInfo)
			.accommodationHost(hostInfo)
			.checkInDateTime(reservation.getCheckIn())
			.checkOutDateTime(reservation.getCheckOut())
			.checkInTime(reservation.getCheckIn().toLocalTime())
			.checkOutTime(reservation.getCheckOut().toLocalTime())
			.paymentInfo(PaymentResponse.PaymentInfo.from(payment))
			.build();
	}

	@Transactional(readOnly = true)
	public ReservationResponse.HostReservationInfos findHostReservations(Long hostId, CursorRequest.CursorPageRequest cursorRequest ) {
		Slice<Reservation> reservationSlice = reservationRepository.findHostReservationsByHostIdWithCursor(
			hostId,
			cursorRequest.lastId(),
			cursorRequest.lastCreatedAt(),
			PageRequest.of(0, cursorRequest.size())
		);

		List<Reservation> reservations = reservationSlice.getContent();

		List<ReservationResponse.HostReservationInfo> reservationInfos = reservations.stream()
			.map(r -> {
				Accommodation accommodation = r.getAccommodation();
				Member guest = r.getGuest();

				return ReservationResponse.HostReservationInfo.builder()
					.reservationUid(r.getReservationUid().toString())
					.status(r.getStatus())
					.accommodationId(accommodation.getId())
					.accommodationName(accommodation.getName())
					.thumbnailUrl(accommodation.getThumbnailUrl())
					.guestId(guest.getId())
					.guestNickName(guest.getNickname())
					.checkInDate(r.getCheckIn().toLocalDate())
					.checkOutDate(r.getCheckOut().toLocalDate())
					.createdAt(r.getCreatedAt())
					.build();
			}).collect(Collectors.toList());

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			reservations,
			reservationSlice.hasNext(),
			Reservation::getId,
			Reservation::getCreatedAt
		);

		return ReservationResponse.HostReservationInfos.builder()
			.reservations(reservationInfos)
			.pageInfo(pageInfo)
			.build();
	}

	@Transactional(readOnly = true)
	public ReservationResponse.HostDetailInfo findHostReservationDetail(String reservationUidStr, Long hostId) {

		UUID reservationUid = UUID.fromString(reservationUidStr);

		Reservation reservation = reservationRepository.findHostReservationDetailByUidAndHostId(reservationUid, hostId)
			.orElseThrow(ReservationNotFoundException::new);

		Payment payment = findPaymentByReservationUidNullable(reservationUid);

		Accommodation accommodation = reservation.getAccommodation();
		Member guest = reservation.getGuest();

		ReservationResponse.GuestInfo guestInfo = ReservationResponse.GuestInfo.builder()
			.id(guest.getId())
			.nickname(guest.getNickname())
			.build();

		PaymentResponse.PaymentInfo paymentInfo = (payment != null) ? PaymentResponse.PaymentInfo.from(payment) : null;

		return ReservationResponse.HostDetailInfo.builder()
			.reservationUid(reservation.getReservationUid().toString())
			.status(reservation.getStatus())
			.createdAt(reservation.getCreatedAt())
			.guestCount(reservation.getGuestCount())
			.message(reservation.getMessage())
			.accommodationId(accommodation.getId())
			.accommodationName(accommodation.getName())
			.checkInDateTime(reservation.getCheckIn())
			.checkOutDateTime(reservation.getCheckOut())
			.guestInfo(guestInfo)
			.paymentInfo(paymentInfo)
			.build();
	}

	private String createConfirmationCode() {
		String confirmationCode;
		do {
			confirmationCode = generateConfirmationCode();
		} while (reservationRepository.existsByConfirmationCode(confirmationCode));

		return confirmationCode;
	}

	private String generateConfirmationCode() {
		return RandomStringUtils.randomAlphanumeric(6).toUpperCase();
	}

	private String buildFullAddress(Address address) {
		return Stream.of(address.getCountry(), address.getCity(), address.getDistrict(), address.getStreet(), address.getDetail())
			.filter(s -> s != null && !s.isBlank())
			.collect(Collectors.joining(" "));
	}

	public Reservation findByReservationUidNullable(String reservationUid) {

		return reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElse(null);
	}

	private Payment findPaymentByReservationUidNullable(UUID reservationUid) {
		return paymentRepository.findByReservationReservationUid(reservationUid)
			.orElse(null);
	}
}
