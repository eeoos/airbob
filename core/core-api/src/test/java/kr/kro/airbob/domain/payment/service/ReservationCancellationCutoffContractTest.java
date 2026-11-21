package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.messaging.outbox.application.OutboxWriter;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@ExtendWith(MockitoExtension.class)
class ReservationCancellationCutoffContractTest {

	private static final UUID RESERVATION_UID =
		UUID.fromString("857369d8-a10a-4fe2-aa24-0ef1a67bd1be");
	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("b9157c88-6e41-4264-b5f0-60f98cb15872");
	private static final long GUEST_ID = 10L;
	private static final long ACCOMMODATION_ID = 31L;
	private static final Instant CHECK_IN_AT = Instant.parse("2026-08-26T06:00:00Z");
	private static final LocalDate CHECK_IN_DATE = LocalDate.of(2026, 8, 26);
	private static final LocalDate CHECK_OUT_DATE = LocalDate.of(2026, 8, 28);

	@Mock private ReservationRepository reservationRepository;
	@Mock private ReservationInventoryService inventoryService;
	@Mock private PaymentRepository paymentRepository;
	@Mock private PaymentOperationRepository operationRepository;
	@Mock private ReservationHistoryRepository historyRepository;
	@Mock private CouponUsageService couponUsageService;
	@Mock private AccommodationSearchRefreshPublisher searchRefreshPublisher;
	@Mock private OutboxWriter outboxWriter;

	@Test
	void rejectsPaidCancellationAtTheCheckInInstant() {
		PaymentCancellationCommandService service = serviceAt(CHECK_IN_AT);
		Reservation reservation = reservation(100_000L);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));

		Throwable thrown = catchThrowable(() -> service.requestCancellation(
			RESERVATION_UID.toString(), cancellationRequest(), GUEST_ID));

		assertCancellationDeadlineContract(thrown);
		then(paymentRepository).shouldHaveNoInteractions();
		then(operationRepository).should().findFirstByReservationIdAndOperationTypeOrderByIdDesc(
			reservation.getId(), PaymentOperationType.CANCEL);
		then(outboxWriter).shouldHaveNoInteractions();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
	}

	@Test
	void rejectsPaidCancellationAfterTheCheckInInstant() {
		PaymentCancellationCommandService service = serviceAt(CHECK_IN_AT.plusSeconds(1));
		Reservation reservation = reservation(100_000L);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));

		Throwable thrown = catchThrowable(() -> service.requestCancellation(
			RESERVATION_UID.toString(), cancellationRequest(), GUEST_ID));

		assertCancellationDeadlineContract(thrown);
		then(paymentRepository).shouldHaveNoInteractions();
		then(operationRepository).should().findFirstByReservationIdAndOperationTypeOrderByIdDesc(
			reservation.getId(), PaymentOperationType.CANCEL);
		then(outboxWriter).shouldHaveNoInteractions();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
	}

	@Test
	void rejectsComplimentaryCancellationAtTheCheckInInstant() {
		PaymentCancellationCommandService service = serviceAt(CHECK_IN_AT);
		Reservation reservation = reservation(0L);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));

		Throwable thrown = catchThrowable(() -> service.requestCancellation(
			RESERVATION_UID.toString(), cancellationRequest(), GUEST_ID));

		assertCancellationDeadlineContract(thrown);
		then(couponUsageService).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
		then(searchRefreshPublisher).shouldHaveNoInteractions();
		then(inventoryService).shouldHaveNoInteractions();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
	}

	@Test
	void rejectsComplimentaryCancellationAfterTheCheckInInstant() {
		PaymentCancellationCommandService service = serviceAt(CHECK_IN_AT.plusSeconds(1));
		Reservation reservation = reservation(0L);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));

		Throwable thrown = catchThrowable(() -> service.requestCancellation(
			RESERVATION_UID.toString(), cancellationRequest(), GUEST_ID));

		assertCancellationDeadlineContract(thrown);
		then(couponUsageService).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
		then(searchRefreshPublisher).shouldHaveNoInteractions();
		then(inventoryService).shouldHaveNoInteractions();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
	}

	@Test
	void replaysAnActivePaidCancellationAfterTheCheckInInstant() {
		PaymentCancellationCommandService service = serviceAt(CHECK_IN_AT.plusSeconds(1));
		Reservation reservation = reservation(100_000L, ReservationStatus.CANCELLATION_PENDING);
		PaymentOperation existing = PaymentOperation.createCancellation(
			reservation,
			GUEST_ID,
			"payment-key",
			100_000L,
			"게스트 요청",
			CHECK_IN_AT.minusSeconds(1)
		);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));
		given(operationRepository.findFirstByReservationIdAndOperationTypeOrderByIdDesc(
			reservation.getId(), PaymentOperationType.CANCEL)).willReturn(Optional.of(existing));

		var response = service.requestCancellation(
			RESERVATION_UID.toString(), cancellationRequest(), GUEST_ID);

		assertThat(response.operationId()).isEqualTo(existing.getOperationUid());
		then(paymentRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void replaysAnAppliedPaidCancellationAfterTheCheckInInstant() {
		PaymentCancellationCommandService service = serviceAt(CHECK_IN_AT.plusSeconds(1));
		Reservation reservation = reservation(100_000L, ReservationStatus.CANCELLED);
		PaymentOperation applied = PaymentOperation.builder()
			.operationUid(UUID.randomUUID())
			.reservation(reservation)
			.operationType(PaymentOperationType.CANCEL)
			.status(PaymentOperationStatus.APPLIED)
			.cancellationReason("게스트 요청")
			.expectedAmount(100_000L)
			.build();
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));
		given(operationRepository.findFirstByReservationIdAndOperationTypeOrderByIdDesc(
			reservation.getId(), PaymentOperationType.CANCEL)).willReturn(Optional.of(applied));

		var response = service.requestCancellation(
			RESERVATION_UID.toString(), cancellationRequest(), GUEST_ID);

		assertThat(response.operationId()).isEqualTo(applied.getOperationUid());
		assertThat(response.status()).isEqualTo(
			kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Status.SUCCEEDED);
		then(paymentRepository).shouldHaveNoInteractions();
	}

	@Test
	void replaysAComplimentaryCancellationAfterTheCheckInInstant() {
		PaymentCancellationCommandService service = serviceAt(CHECK_IN_AT.plusSeconds(1));
		Reservation reservation = reservation(0L, ReservationStatus.CANCELLED);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));

		var response = service.requestCancellation(
			RESERVATION_UID.toString(), cancellationRequest(), GUEST_ID);

		assertThat(response.completedSynchronously()).isTrue();
		then(couponUsageService).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
		then(inventoryService).shouldHaveNoInteractions();
	}

	@Test
	void allowsPaidCancellationImmediatelyBeforeTheCheckInInstant() {
		PaymentCancellationCommandService service = serviceAt(CHECK_IN_AT.minusNanos(1));
		Reservation reservation = reservation(100_000L);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));
		given(operationRepository.findFirstByReservationIdAndOperationTypeOrderByIdDesc(
			reservation.getId(), PaymentOperationType.CANCEL)).willReturn(Optional.empty());
		given(paymentRepository.findByReservationIdWithLock(reservation.getId()))
			.willReturn(Optional.of(payment(reservation)));

		var response = service.requestCancellation(
			RESERVATION_UID.toString(), cancellationRequest(), GUEST_ID);

		assertThat(response.completedSynchronously()).isFalse();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_PENDING);
		then(operationRepository).should().save(any(PaymentOperation.class));
		then(outboxWriter).should().append(any());
		then(inventoryService).shouldHaveNoInteractions();
	}

	@Test
	void allowsComplimentaryCancellationImmediatelyBeforeTheCheckInInstant() {
		PaymentCancellationCommandService service = serviceAt(CHECK_IN_AT.minusNanos(1));
		Reservation reservation = reservation(0L);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));

		var response = service.requestCancellation(
			RESERVATION_UID.toString(), cancellationRequest(), GUEST_ID);

		assertThat(response.completedSynchronously()).isTrue();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
		then(couponUsageService).should().restore(reservation.getId());
		then(searchRefreshPublisher).should().requestRefresh(ACCOMMODATION_UID);
		then(inventoryService).should().releaseOccupied(
			ACCOMMODATION_ID,
			CHECK_IN_DATE,
			CHECK_OUT_DATE,
			reservation.getId()
		);
	}

	private PaymentCancellationCommandService serviceAt(Instant now) {
		return new PaymentCancellationCommandService(
			reservationRepository,
			inventoryService,
			paymentRepository,
			operationRepository,
			historyRepository,
			couponUsageService,
			searchRefreshPublisher,
			outboxWriter,
			Clock.fixed(now, ZoneOffset.UTC)
		);
	}

	private Reservation reservation(long totalPrice) {
		return reservation(totalPrice, ReservationStatus.CONFIRMED);
	}

	private Reservation reservation(long totalPrice, ReservationStatus status) {
		return Reservation.builder()
			.id(1L)
			.reservationUid(RESERVATION_UID)
			.guest(Member.builder().id(GUEST_ID).build())
			.accommodation(Accommodation.builder()
				.id(ACCOMMODATION_ID)
				.accommodationUid(ACCOMMODATION_UID)
				.build())
			.checkInDate(CHECK_IN_DATE)
			.checkOutDate(CHECK_OUT_DATE)
			.checkInAt(CHECK_IN_AT)
			.totalPrice(totalPrice)
			.status(status)
			.build();
	}

	private Payment payment(Reservation reservation) {
		return Payment.builder()
			.id(2L)
			.paymentKey("payment-key")
			.orderId(RESERVATION_UID.toString())
			.amount(100_000L)
			.balanceAmount(100_000L)
			.method(PaymentMethod.CARD)
			.status(PaymentStatus.DONE)
			.reservation(reservation)
			.build();
	}

	private PaymentRequest.Cancel cancellationRequest() {
		return new PaymentRequest.Cancel("게스트 요청", null);
	}

	private void assertCancellationDeadlineContract(Throwable thrown) {
		assertThat(thrown).isInstanceOf(BaseException.class);
		assertThat(thrown.getClass().getSimpleName())
			.isEqualTo("ReservationCancellationDeadlinePassedException");
		BaseException exception = (BaseException)thrown;
		assertThat(exception.getErrorCode().name())
			.isEqualTo("RESERVATION_CANCELLATION_DEADLINE_PASSED");
		assertThat(exception.getErrorCode().getCode()).isEqualTo("R015");
		assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.CONFLICT);
	}
}
