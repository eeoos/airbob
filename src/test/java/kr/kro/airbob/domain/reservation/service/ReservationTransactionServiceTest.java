package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.repository.PaymentAttemptRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.entity.ReservationStatusHistory;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationAccessDeniedException;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationStatusHistoryRepository;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationTransactionService 테스트")
class ReservationTransactionServiceTest {

	@InjectMocks
	private ReservationTransactionService transactionService;

	@Mock
	private OutboxEventPublisher outboxEventPublisher;
	@Mock
	private CursorPageInfoCreator cursorPageInfoCreator;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private ReviewRepository reviewRepository;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private ReservationRepository reservationRepository;
	@Mock
	private AccommodationRepository accommodationRepository;
	@Mock
	private PaymentAttemptRepository paymentAttemptRepository;
	@Mock
	private ReservationStatusHistoryRepository historyRepository;

	@Captor
	private ArgumentCaptor<Reservation> reservationCaptor;
	@Captor
	private ArgumentCaptor<ReservationStatusHistory> historyCaptor;

	private Member guest;
	private Accommodation accommodation;
	private ReservationRequest.Create validRequest;
	private Long memberId;

	private Member host;

	@BeforeEach
	void setUp() {
		memberId = 1L;

		guest = Member.builder()
			.id(memberId)
			.email("guest@test.com")
			.nickname("TestGuest")
			.build();

		host = Member.builder()
			.id(2L)
			.email("host@test.com")
			.nickname("TestHost")
			.build();

		accommodation = Accommodation.builder()
			.id(1L)
			.accommodationUid(UUID.randomUUID())
			.name("Test Accommodation")
			.basePrice(100_000L)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.status(AccommodationStatus.PUBLISHED)
			.member(host)
			.build();

		validRequest = new ReservationRequest.Create(
			1L,
			LocalDate.of(2025, 1, 26),
			LocalDate.of(2025, 1, 28),
			2
		);
	}

	@Nested
	@DisplayName("예약 생성 트랜잭션 테스트")
	class CreatePendingReservationInTxTest {

		@Test
		@DisplayName("정상적인 예약 생성 시 Reservation이 저장되고 이벤트가 발행된다")
		void 정상_예약_생성() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findByIdAndStatus(validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));
			given(reservationRepository.existsConflictingReservation(anyLong(), any(LocalDateTime.class), any(LocalDateTime.class)))
				.willReturn(false);
			given(reservationRepository.existsByReservationCode(anyString()))
				.willReturn(false);
			// save() 호출 시 reservationUid가 설정된 상태로 반환 (실제 JPA에서 @PrePersist로 설정됨)
			given(reservationRepository.save(any(Reservation.class)))
				.willAnswer(invocation -> {
					Reservation reservation = invocation.getArgument(0);
					// 리플렉션으로 reservationUid 설정 (실제 @PrePersist 동작 모방)
					if (reservation.getReservationUid() == null) {
						java.lang.reflect.Field uidField = Reservation.class.getDeclaredField("reservationUid");
						uidField.setAccessible(true);
						uidField.set(reservation, UUID.randomUUID());
					}
					return reservation;
				});

			// when
			Reservation result = transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성");

			// then
			assertThat(result).isNotNull();
			assertThat(result.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
			assertThat(result.getTotalPrice()).isEqualTo(200_000L); // 2박 * 100,000원
			assertThat(result.getGuestCount()).isEqualTo(2);

			// verify reservation saved
			then(reservationRepository).should().save(any(Reservation.class));

			// verify history saved
			then(historyRepository).should().save(historyCaptor.capture());
			ReservationStatusHistory savedHistory = historyCaptor.getValue();
			assertThat(savedHistory.getPreviousStatus()).isNull();
			assertThat(savedHistory.getNewStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
			assertThat(savedHistory.getChangedBy()).isEqualTo("USER_ID:" + memberId);

			// verify event published
			then(outboxEventPublisher).should().save(eq(EventType.RESERVATION_PENDING), any(ReservationEvent.ReservationPendingEvent.class));
		}

		@Test
		@DisplayName("회원이 존재하지 않으면 MemberNotFoundException이 발생한다")
		void 예외_회원_미존재() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성"))
				.isInstanceOf(MemberNotFoundException.class);

			then(reservationRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("숙소가 존재하지 않으면 AccommodationNotFoundException이 발생한다")
		void 예외_숙소_미존재() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findByIdAndStatus(validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성"))
				.isInstanceOf(AccommodationNotFoundException.class);

			then(reservationRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("날짜가 충돌하면 ReservationConflictException이 발생한다")
		void 예외_날짜_충돌() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findByIdAndStatus(validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));
			given(reservationRepository.existsConflictingReservation(anyLong(), any(LocalDateTime.class), any(LocalDateTime.class)))
				.willReturn(true);

			// when & then
			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성"))
				.isInstanceOf(ReservationConflictException.class);

			then(reservationRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("예약 코드가 중복되면 재생성한다")
		void 예약코드_중복시_재생성() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findByIdAndStatus(validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));
			given(reservationRepository.existsConflictingReservation(anyLong(), any(LocalDateTime.class), any(LocalDateTime.class)))
				.willReturn(false);
			// 첫 번째 코드는 중복, 두 번째는 유일
			given(reservationRepository.existsByReservationCode(anyString()))
				.willReturn(true)
				.willReturn(false);
			// save() 호출 시 reservationUid가 설정된 상태로 반환
			given(reservationRepository.save(any(Reservation.class)))
				.willAnswer(invocation -> {
					Reservation reservation = invocation.getArgument(0);
					if (reservation.getReservationUid() == null) {
						java.lang.reflect.Field uidField = Reservation.class.getDeclaredField("reservationUid");
						uidField.setAccessible(true);
						uidField.set(reservation, UUID.randomUUID());
					}
					return reservation;
				});

			// when
			Reservation result = transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성");

			// then
			assertThat(result).isNotNull();
			// 코드 중복 체크가 2번 호출됨
			then(reservationRepository).should(times(2)).existsByReservationCode(anyString());
		}
	}

	@Nested
	@DisplayName("예약 확정 트랜잭션 테스트")
	class ConfirmReservationInTxTest {

		@Test
		@DisplayName("PAYMENT_PENDING 상태에서 확정 시 CONFIRMED로 변경된다")
		void 정상_확정() {
			// given
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.PAYMENT_PENDING);

			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			// when
			transactionService.confirmReservationInTx(reservationUid.toString());

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

			then(historyRepository).should().save(historyCaptor.capture());
			ReservationStatusHistory savedHistory = historyCaptor.getValue();
			assertThat(savedHistory.getPreviousStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
			assertThat(savedHistory.getNewStatus()).isEqualTo(ReservationStatus.CONFIRMED);

			then(outboxEventPublisher).should().save(eq(EventType.RESERVATION_CONFIRMED), any(ReservationEvent.ReservationConfirmedEvent.class));
		}

		@Test
		@DisplayName("이미 CONFIRMED 상태면 조기 반환한다 (멱등성)")
		void 멱등성_이미_확정() {
			// given
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.CONFIRMED);

			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			// when
			transactionService.confirmReservationInTx(reservationUid.toString());

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
			then(historyRepository).should(never()).save(any());
			then(outboxEventPublisher).should(never()).save(any(), any());
		}

		@Test
		@DisplayName("예약이 존재하지 않으면 ReservationNotFoundException이 발생한다")
		void 예외_예약_미존재() {
			// given
			UUID reservationUid = UUID.randomUUID();
			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> transactionService.confirmReservationInTx(reservationUid.toString()))
				.isInstanceOf(ReservationNotFoundException.class);
		}
	}

	@Nested
	@DisplayName("예약 만료 트랜잭션 테스트")
	class ExpireReservationInTxTest {

		@Test
		@DisplayName("PAYMENT_PENDING 상태에서 만료 시 EXPIRED로 변경된다")
		void 정상_만료() {
			// given
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.PAYMENT_PENDING);

			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			// when
			transactionService.expireReservationInTx(reservationUid.toString(), "결제 시간 초과");

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);

			then(historyRepository).should().save(historyCaptor.capture());
			ReservationStatusHistory savedHistory = historyCaptor.getValue();
			assertThat(savedHistory.getPreviousStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
			assertThat(savedHistory.getNewStatus()).isEqualTo(ReservationStatus.EXPIRED);

			then(outboxEventPublisher).should().save(eq(EventType.RESERVATION_EXPIRED), any(ReservationEvent.ReservationExpiredEvent.class));
		}

		@Test
		@DisplayName("이미 EXPIRED 상태면 조기 반환한다 (멱등성)")
		void 멱등성_이미_만료() {
			// given
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.EXPIRED);

			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			// when
			transactionService.expireReservationInTx(reservationUid.toString(), "결제 시간 초과");

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
			then(historyRepository).should(never()).save(any());
			then(outboxEventPublisher).should(never()).save(any(), any());
		}

		@Test
		@DisplayName("예약이 존재하지 않으면 ReservationNotFoundException이 발생한다")
		void 예외_예약_미존재() {
			// given
			UUID reservationUid = UUID.randomUUID();
			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> transactionService.expireReservationInTx(reservationUid.toString(), "결제 시간 초과"))
				.isInstanceOf(ReservationNotFoundException.class);
		}
	}

	@Nested
	@DisplayName("예약 취소 트랜잭션 테스트")
	class CancelReservationInTxTest {

		@Test
		@DisplayName("CONFIRMED 상태에서 본인이 취소 시 CANCELLED로 변경된다")
		void 정상_취소() {
			// given
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createConfirmedReservationWithGuest(reservationUid, guest);
			PaymentRequest.Cancel cancelRequest = new PaymentRequest.Cancel("사용자 취소 요청", 200_000L);

			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			// when
			transactionService.cancelReservationInTx(reservationUid.toString(), cancelRequest, memberId);

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);

			then(historyRepository).should().save(historyCaptor.capture());
			ReservationStatusHistory savedHistory = historyCaptor.getValue();
			assertThat(savedHistory.getPreviousStatus()).isEqualTo(ReservationStatus.CONFIRMED);
			assertThat(savedHistory.getNewStatus()).isEqualTo(ReservationStatus.CANCELLED);
			assertThat(savedHistory.getReason()).isEqualTo("사용자 취소 요청");

			then(outboxEventPublisher).should().save(eq(EventType.RESERVATION_CANCELLED), any(ReservationEvent.ReservationCancelledEvent.class));
		}

		@Test
		@DisplayName("예약이 존재하지 않으면 ReservationNotFoundException이 발생한다")
		void 예외_예약_미존재() {
			// given
			UUID reservationUid = UUID.randomUUID();
			PaymentRequest.Cancel cancelRequest = new PaymentRequest.Cancel("사용자 취소 요청", 200_000L);

			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> transactionService.cancelReservationInTx(reservationUid.toString(), cancelRequest, memberId))
				.isInstanceOf(ReservationNotFoundException.class);
		}

		@Test
		@DisplayName("본인이 아니면 ReservationAccessDeniedException이 발생한다")
		void 예외_권한_없음() {
			// given
			UUID reservationUid = UUID.randomUUID();
			Member anotherGuest = Member.builder()
				.id(999L)
				.email("another@test.com")
				.nickname("AnotherGuest")
				.build();
			Reservation reservation = createConfirmedReservationWithGuest(reservationUid, anotherGuest);
			PaymentRequest.Cancel cancelRequest = new PaymentRequest.Cancel("사용자 취소 요청", 200_000L);

			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			// when & then
			assertThatThrownBy(() -> transactionService.cancelReservationInTx(reservationUid.toString(), cancelRequest, memberId))
				.isInstanceOf(ReservationAccessDeniedException.class);
		}
	}

	@Nested
	@DisplayName("취소 보상 트랜잭션 테스트")
	class RevertCancellationInTxTest {

		@Test
		@DisplayName("CANCELLED 상태에서 보상 시 CANCELLATION_FAILED로 변경된다")
		void 정상_보상() {
			// given
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.CANCELLED);

			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			// when
			transactionService.revertCancellationInTx(reservationUid.toString(), "환불 처리 실패");

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);

			then(historyRepository).should().save(historyCaptor.capture());
			ReservationStatusHistory savedHistory = historyCaptor.getValue();
			assertThat(savedHistory.getPreviousStatus()).isEqualTo(ReservationStatus.CANCELLED);
			assertThat(savedHistory.getNewStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
		}

		@Test
		@DisplayName("이미 CANCELLATION_FAILED 상태면 조기 반환한다 (멱등성)")
		void 멱등성_이미_실패() {
			// given
			UUID reservationUid = UUID.randomUUID();
			Reservation reservation = createReservationWithStatus(reservationUid, ReservationStatus.CANCELLATION_FAILED);

			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			// when
			transactionService.revertCancellationInTx(reservationUid.toString(), "환불 처리 실패");

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
			then(historyRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("예약이 존재하지 않으면 ReservationNotFoundException이 발생한다")
		void 예외_예약_미존재() {
			// given
			UUID reservationUid = UUID.randomUUID();
			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> transactionService.revertCancellationInTx(reservationUid.toString(), "환불 처리 실패"))
				.isInstanceOf(ReservationNotFoundException.class);
		}
	}

	private Reservation createReservationWithStatus(UUID reservationUid, ReservationStatus status) {
		return Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.reservationCode("ABC123")
			.accommodation(accommodation)
			.guest(guest)
			.checkIn(LocalDateTime.of(2025, 1, 26, 15, 0))
			.checkOut(LocalDateTime.of(2025, 1, 28, 11, 0))
			.guestCount(2)
			.totalPrice(200_000L)
			.currency("KRW")
			.status(status)
			.expiresAt(LocalDateTime.now().plusMinutes(15))
			.build();
	}

	private Reservation createConfirmedReservationWithGuest(UUID reservationUid, Member guestMember) {
		return Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.reservationCode("ABC123")
			.accommodation(accommodation)
			.guest(guestMember)
			.checkIn(LocalDateTime.of(2025, 1, 26, 15, 0))
			.checkOut(LocalDateTime.of(2025, 1, 28, 11, 0))
			.guestCount(2)
			.totalPrice(200_000L)
			.currency("KRW")
			.status(ReservationStatus.CONFIRMED)
			.expiresAt(LocalDateTime.now().plusMinutes(15))
			.build();
	}
}
