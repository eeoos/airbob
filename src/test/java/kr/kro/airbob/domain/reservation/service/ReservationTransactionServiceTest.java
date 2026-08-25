package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.payment.repository.PaymentTransactionRepository;
import kr.kro.airbob.domain.reservation.command.ReservationCreateCommand;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationOutsideBookingWindowException;
import kr.kro.airbob.domain.reservation.exception.ReservationOccupancyExceededException;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationHoldPolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationQuoteRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationCheckoutRequestStore;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationTransactionService 테스트")
class ReservationTransactionServiceTest {
	private static final String TIME_ZONE_ID = "America/New_York";
	private static final LocalDate WINDOW_START = LocalDate.of(2026, 8, 11);
	private static final Instant NOW = Instant.parse("2026-08-11T15:00:00Z");

	private ReservationTransactionService transactionService;

	@Mock
	private AccommodationSearchRefreshPublisher searchRefreshPublisher;
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
	private PaymentTransactionRepository paymentTransactionRepository;
	@Mock
	private ReservationHistoryRepository historyRepository;
	@Mock
	private CouponUsageService couponUsageService;
	@Mock
	private BookingWindowProvider bookingWindowProvider;
	@Mock
	private ReservationCheckoutRequestStore checkoutRequestStore;
	@Mock
	private ReservationQuoteRepository quoteRepository;
	@Mock
	private ReservationInventoryService inventoryService;
	private ReservationHoldPolicy holdPolicy;

	@Captor
	private ArgumentCaptor<Reservation> reservationCaptor;
	@Captor
	private ArgumentCaptor<ReservationHistory> historyCaptor;

	private Member guest;
	private Accommodation accommodation;
	private ReservationCreateCommand validRequest;
	private Long memberId;

	private Member host;

	@BeforeEach
	void setUp() {
		holdPolicy = new ReservationHoldPolicy(Duration.ofMinutes(7));
		transactionService = new ReservationTransactionService(
			searchRefreshPublisher,
			cursorPageInfoCreator,
			memberRepository,
			reviewRepository,
			paymentRepository,
			reservationRepository,
			accommodationRepository,
			paymentTransactionRepository,
			historyRepository,
			couponUsageService,
			bookingWindowProvider,
			holdPolicy,
			quoteRepository,
			checkoutRequestStore,
			inventoryService,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
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
			.timeZoneId(TIME_ZONE_ID)
			.status(AccommodationStatus.PUBLISHED)
			.occupancyPolicy(OccupancyPolicy.builder().maxOccupancy(2).build())
			.member(host)
			.build();

		validRequest = new ReservationCreateCommand(
			1L,
			LocalDate.of(2026, 8, 12),
			LocalDate.of(2026, 8, 14),
			2,
			null,
			"조용한 방으로 부탁드립니다"
		);

		lenient().when(bookingWindowProvider.currentFor(TIME_ZONE_ID, NOW))
			.thenReturn(BookingWindow.startingOn(WINDOW_START));
	}

	@Test
	@DisplayName("체크아웃 시각과 현재 시각이 같으면 리뷰를 작성할 수 있다")
	void allowsReviewAtExactCheckoutInstant() {
		UUID reservationUid = UUID.randomUUID();
		Reservation reservation = Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.reservationCode("ABC123")
			.accommodation(accommodation)
			.guest(guest)
			.checkInDate(LocalDate.of(2026, 8, 12))
			.checkOutDate(LocalDate.of(2026, 8, 14))
			.checkInAt(Instant.parse("2026-08-12T19:00:00Z"))
			.checkOutAt(NOW)
			.timeZoneId(TIME_ZONE_ID)
			.guestCount(2)
			.totalPrice(200_000L)
			.currency("KRW")
			.status(ReservationStatus.CONFIRMED)
			.expiresAt(Instant.parse("2026-08-12T18:15:00Z"))
			.build();
		given(reservationRepository.findReservationDetailByUidAndGuestId(reservationUid, memberId))
			.willReturn(Optional.of(reservation));
		given(reviewRepository.existsByAccommodationIdAndAuthorIdAndStatus(
			accommodation.getId(), memberId, kr.kro.airbob.domain.review.entity.ReviewStatus.PUBLISHED))
			.willReturn(false);

		var response = transactionService.findMyReservationDetail(reservationUid.toString(), memberId);

		assertThat(response.canWriteReview()).isTrue();
	}

	@Test
	@DisplayName("체크아웃했어도 취소 처리 중이면 리뷰를 작성할 수 없다")
	void cancellationPendingIsNotReviewable() {
		UUID reservationUid = UUID.randomUUID();
		Reservation reservation = createReservationWithStatus(
			reservationUid, ReservationStatus.CANCELLATION_PENDING);
		given(reservationRepository.findReservationDetailByUidAndGuestId(reservationUid, memberId))
			.willReturn(Optional.of(reservation));

		var response = transactionService.findMyReservationDetail(reservationUid.toString(), memberId);

		assertThat(response.canWriteReview()).isFalse();
		then(reviewRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("취소 실패로 예약이 유효하면 체크아웃 후 리뷰를 작성할 수 있다")
	void cancellationFailedIsReviewable() {
		UUID reservationUid = UUID.randomUUID();
		Reservation reservation = createReservationWithStatus(
			reservationUid, ReservationStatus.CANCELLATION_FAILED);
		given(reservationRepository.findReservationDetailByUidAndGuestId(reservationUid, memberId))
			.willReturn(Optional.of(reservation));
		given(reviewRepository.existsByAccommodationIdAndAuthorIdAndStatus(
			accommodation.getId(), memberId, kr.kro.airbob.domain.review.entity.ReviewStatus.PUBLISHED))
			.willReturn(false);

		var response = transactionService.findMyReservationDetail(reservationUid.toString(), memberId);

		assertThat(response.canWriteReview()).isTrue();
	}

	@Nested
	@DisplayName("예약 생성 트랜잭션 테스트")
	class CreatePendingReservationInTxTest {

		@Test
		@DisplayName("숙소 최대 정원을 초과하면 예약·쿠폰·이벤트 쓰기 전에 거부한다")
		void rejectsGuestCountOverAccommodationCapacityBeforeWrites() {
			ReservationCreateCommand overCapacity = new ReservationCreateCommand(
				accommodation.getId(), WINDOW_START.plusDays(1), WINDOW_START.plusDays(2), 3);
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findBookingSnapshotForShare(
				overCapacity.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));

			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(
				overCapacity, memberId, "정원 검증"))
				.isInstanceOf(ReservationOccupancyExceededException.class);

			then(bookingWindowProvider).shouldHaveNoInteractions();
			then(reservationRepository).shouldHaveNoInteractions();
			then(couponUsageService).shouldHaveNoInteractions();
			then(historyRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("정상적인 예약 생성 시 Reservation이 저장되고 이벤트가 발행된다")
		void 정상_예약_생성() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findBookingSnapshotForShare(
				validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));
			given(reservationRepository.existsByReservationCode(anyString()))
				.willReturn(false);
			// save() 호출 시 reservationUid가 설정된 상태로 반환 (실제 JPA에서 @PrePersist로 설정됨)
			given(reservationRepository.saveAndFlush(any(Reservation.class)))
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
			assertThat(result.getCheckInDate()).isEqualTo(validRequest.checkInDate());
			assertThat(result.getCheckOutDate()).isEqualTo(validRequest.checkOutDate());
			assertThat(result.getCheckInAt()).isEqualTo(Instant.parse("2026-08-12T19:00:00Z"));
			assertThat(result.getCheckOutAt()).isEqualTo(Instant.parse("2026-08-14T15:00:00Z"));
			assertThat(result.getTimeZoneId()).isEqualTo(TIME_ZONE_ID);
			assertThat(result.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(7)));
			assertThat(result.getMessage()).isEqualTo("조용한 방으로 부탁드립니다");
			then(accommodationRepository).should().findBookingSnapshotForShare(
				validRequest.accommodationId(), AccommodationStatus.PUBLISHED);

			// verify reservation saved
			then(reservationRepository).should().saveAndFlush(any(Reservation.class));

			// verify history saved
			then(historyRepository).should().save(historyCaptor.capture());
			ReservationHistory savedHistory = historyCaptor.getValue();
			assertThat(savedHistory.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
			assertThat(savedHistory.getChangeType()).isEqualTo(ChangeType.CREATE);
			assertThat(savedHistory.getReservationId()).isEqualTo(result.getId());
			assertThat(savedHistory.getCheckInDate()).isEqualTo(result.getCheckInDate());
			assertThat(savedHistory.getCheckOutDate()).isEqualTo(result.getCheckOutDate());
			assertThat(savedHistory.getCheckInAt()).isEqualTo(result.getCheckInAt());
			assertThat(savedHistory.getCheckOutAt()).isEqualTo(result.getCheckOutAt());
			assertThat(savedHistory.getTimeZoneId()).isEqualTo(result.getTimeZoneId());
			assertThat(savedHistory.getExpiresAt()).isEqualTo(result.getExpiresAt());

			then(searchRefreshPublisher).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("쿠폰이 전액을 할인하면 PG 작업 없이 예약을 즉시 확정하고 재고 이벤트를 발행한다")
		void fullDiscountConfirmsWithoutPaymentOperation() throws Exception {
			ReservationCreateCommand complimentaryRequest = new ReservationCreateCommand(
				accommodation.getId(), WINDOW_START.plusDays(1), WINDOW_START.plusDays(3), 2, 77L);
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findBookingSnapshotForShare(
				complimentaryRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));
			given(reservationRepository.existsByReservationCode(anyString())).willReturn(false);
			given(reservationRepository.saveAndFlush(any(Reservation.class))).willAnswer(invocation -> {
				Reservation reservation = invocation.getArgument(0);
				java.lang.reflect.Field idField = Reservation.class.getDeclaredField("id");
				idField.setAccessible(true);
				idField.set(reservation, 99L);
				java.lang.reflect.Field uidField = Reservation.class.getDeclaredField("reservationUid");
				uidField.setAccessible(true);
				uidField.set(reservation, UUID.randomUUID());
				return reservation;
			});
			given(couponUsageService.use(memberId, 77L, 99L, 200_000L)).willReturn(200_000L);

			Reservation result = transactionService.createPendingReservationInTx(
				complimentaryRequest, memberId, "0원 예약");

			assertThat(result.getTotalPrice()).isZero();
			assertThat(result.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
			then(searchRefreshPublisher).should().requestRefresh(
				accommodation.getAccommodationUid());
		}

		@Test
		@DisplayName("DST 전환을 지나는 숙박도 숙소 현지 시각을 정확한 절대 시각으로 변환한다")
		void conflictCheckUsesAccommodationZoneInstantsAcrossDst() {
			ReservationCreateCommand dstRequest = new ReservationCreateCommand(
				accommodation.getId(),
				LocalDate.of(2027, 3, 13),
				LocalDate.of(2027, 3, 15),
				2
			);
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findBookingSnapshotForShare(
				dstRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));
			given(bookingWindowProvider.currentFor(TIME_ZONE_ID, NOW))
				.willReturn(BookingWindow.startingOn(LocalDate.of(2027, 3, 1)));
			given(reservationRepository.existsByReservationCode(anyString()))
				.willReturn(false);
			given(reservationRepository.saveAndFlush(any(Reservation.class)))
				.willAnswer(invocation -> {
					Reservation reservation = invocation.getArgument(0);
					java.lang.reflect.Field uidField = Reservation.class.getDeclaredField("reservationUid");
					uidField.setAccessible(true);
					uidField.set(reservation, UUID.randomUUID());
					return reservation;
				});

			Reservation result = transactionService.createPendingReservationInTx(
				dstRequest, memberId, "DST 경계 예약 생성");

			Instant expectedCheckInAt = Instant.parse("2027-03-13T20:00:00Z");
			Instant expectedCheckOutAt = Instant.parse("2027-03-15T15:00:00Z");
			then(inventoryService).should().lockAvailableRangeNowait(
				accommodation.getId(), dstRequest.checkInDate(), dstRequest.checkOutDate(), NOW);
			assertThat(result.getCheckInAt()).isEqualTo(expectedCheckInAt);
			assertThat(result.getCheckOutAt()).isEqualTo(expectedCheckOutAt);
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

			then(reservationRepository).should(never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("숙소가 존재하지 않으면 AccommodationNotFoundException이 발생한다")
		void 예외_숙소_미존재() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findBookingSnapshotForShare(
				validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성"))
				.isInstanceOf(AccommodationNotFoundException.class);

			then(reservationRepository).should(never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("체크아웃이 체크인보다 이후가 아니면 범위와 충돌 검사 전에 거부한다")
		void 예외_잘못된_숙박_기간() {
			ReservationCreateCommand invalidRequest = new ReservationCreateCommand(
				1L,
				LocalDate.of(2026, 8, 12),
				LocalDate.of(2026, 8, 12),
				2
			);
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findBookingSnapshotForShare(
				invalidRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));

			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(
				invalidRequest, memberId, "사용자 예약 생성"))
				.isInstanceOf(InvalidReservationDateException.class);

			then(bookingWindowProvider).shouldHaveNoInteractions();
			then(reservationRepository).shouldHaveNoInteractions();
			then(historyRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("숙소 현지 예약 가능 기간을 벗어나면 충돌과 쓰기 전에 거부한다")
		void 예외_예약_가능_기간_초과() {
			ReservationCreateCommand outsideRequest = new ReservationCreateCommand(
				1L,
				WINDOW_START.plusMonths(3),
				WINDOW_START.plusMonths(3).plusDays(1),
				2
			);
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findBookingSnapshotForShare(
				outsideRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));

			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(
				outsideRequest, memberId, "사용자 예약 생성"))
				.isInstanceOf(ReservationOutsideBookingWindowException.class);

			then(bookingWindowProvider).should().currentFor(TIME_ZONE_ID, NOW);
			then(reservationRepository).shouldHaveNoInteractions();
			then(historyRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("날짜가 충돌하면 ReservationConflictException이 발생한다")
		void 예외_날짜_충돌() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findBookingSnapshotForShare(
				validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));
			given(inventoryService.lockAvailableRangeNowait(
				anyLong(), any(LocalDate.class), any(LocalDate.class), any(Instant.class)))
				.willThrow(new ReservationConflictException());

			// when & then
			assertThatThrownBy(() -> transactionService.createPendingReservationInTx(validRequest, memberId, "사용자 예약 생성"))
				.isInstanceOf(ReservationConflictException.class);

			then(reservationRepository).should(never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("예약 코드가 중복되면 재생성한다")
		void 예약코드_중복시_재생성() {
			// given
			given(memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE))
				.willReturn(Optional.of(guest));
			given(accommodationRepository.findBookingSnapshotForShare(
				validRequest.accommodationId(), AccommodationStatus.PUBLISHED))
				.willReturn(Optional.of(accommodation));
			// 첫 번째 코드는 중복, 두 번째는 유일
			given(reservationRepository.existsByReservationCode(anyString()))
				.willReturn(true)
				.willReturn(false);
			// save() 호출 시 reservationUid가 설정된 상태로 반환
			given(reservationRepository.saveAndFlush(any(Reservation.class)))
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


	private Reservation createReservationWithStatus(UUID reservationUid, ReservationStatus status) {
		return Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.reservationCode("ABC123")
			.accommodation(accommodation)
			.guest(guest)
			.checkInDate(LocalDate.of(2025, 1, 26))
			.checkOutDate(LocalDate.of(2025, 1, 28))
			.checkInAt(Instant.parse("2025-01-26T20:00:00Z"))
			.checkOutAt(Instant.parse("2025-01-28T16:00:00Z"))
			.timeZoneId(TIME_ZONE_ID)
			.guestCount(2)
			.totalPrice(200_000L)
			.currency("KRW")
			.status(status)
			.expiresAt(NOW.plusSeconds(15 * 60))
			.build();
	}

}
