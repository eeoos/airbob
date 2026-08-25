package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Cancellation;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Status;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ReservationInventoryInvariantViolationException;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.messaging.outbox.application.OutboxWriter;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@ExtendWith(MockitoExtension.class)
class PaymentCancellationCommandServiceTest {

	private static final UUID RESERVATION_UID =
		UUID.fromString("857369d8-a10a-4fe2-aa24-0ef1a67bd1be");
	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("b9157c88-6e41-4264-b5f0-60f98cb15872");
	private static final long GUEST_ID = 10L;
	private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
	private static final LocalDate CHECK_IN = LocalDate.of(2026, 8, 18);
	private static final LocalDate CHECK_OUT = LocalDate.of(2026, 8, 20);
	private static final long ACCOMMODATION_ID = 31L;

	@Mock private ReservationRepository reservationRepository;
	@Mock private ReservationInventoryService inventoryService;
	@Mock private PaymentRepository paymentRepository;
	@Mock private PaymentOperationRepository operationRepository;
	@Mock private ReservationHistoryRepository historyRepository;
	@Mock private CouponUsageService couponUsageService;
	@Mock private AccommodationSearchRefreshPublisher searchRefreshPublisher;
	@Mock private OutboxWriter outboxWriter;

	private PaymentCancellationCommandService service;

	@BeforeEach
	void setUp() {
		service = new PaymentCancellationCommandService(
			reservationRepository,
			inventoryService,
			paymentRepository,
			operationRepository,
			historyRepository,
			couponUsageService,
			searchRefreshPublisher,
			outboxWriter,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	void paidCancellationCreatesOneDurableCancelOperationAndExecutionSignal() {
		Reservation reservation = reservation(ReservationStatus.CONFIRMED, 100_000L);
		Payment payment = payment(reservation, 100_000L, 100_000L, PaymentStatus.DONE);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));
		given(operationRepository.findFirstByReservationIdAndOperationTypeOrderByIdDesc(
			1L, PaymentOperationType.CANCEL)).willReturn(Optional.empty());
		given(paymentRepository.findByReservationIdWithLock(1L)).willReturn(Optional.of(payment));

		Cancellation response = service.requestCancellation(
			RESERVATION_UID.toString(), new PaymentRequest.Cancel("게스트 요청", null), GUEST_ID);

		assertThat(response.operationId()).isNotNull();
		assertThat(response.status()).isEqualTo(Status.PENDING);
		assertThat(response.statusUrl())
			.isEqualTo("/api/v1/payment-operations/" + response.operationId());
		assertThat(response.completedSynchronously()).isFalse();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_PENDING);
		then(inventoryService).shouldHaveNoInteractions();

		ArgumentCaptor<PaymentOperation> operation = ArgumentCaptor.forClass(PaymentOperation.class);
		then(operationRepository).should().save(operation.capture());
		assertThat(operation.getValue().getOperationType()).isEqualTo(PaymentOperationType.CANCEL);
		assertThat(operation.getValue().getPaymentKey()).isEqualTo("payment-key");
		assertThat(operation.getValue().getExpectedAmount()).isEqualTo(100_000L);
		assertThat(operation.getValue().getCancellationReason()).isEqualTo("게스트 요청");

		ArgumentCaptor<PaymentOperationExecutionRequestedV1> event =
			ArgumentCaptor.forClass(PaymentOperationExecutionRequestedV1.class);
		then(outboxWriter).should().append(event.capture());
		assertThat(event.getValue().operationUid()).isEqualTo(response.operationId());
		assertThat(event.getValue().reservationUid()).isEqualTo(RESERVATION_UID);
		assertThat(event.getValue().dispatchGeneration()).isOne();
	}

	@Test
	void duplicateWhileCancellationIsActiveReturnsTheExistingOperation() {
		Reservation reservation = reservation(ReservationStatus.CANCELLATION_PENDING, 100_000L);
		PaymentOperation existing = PaymentOperation.createCancellation(
			reservation, GUEST_ID, "payment-key", 100_000L, "첫 요청", NOW.minusSeconds(1));
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));
		given(operationRepository.findFirstByReservationIdAndOperationTypeOrderByIdDesc(
			1L, PaymentOperationType.CANCEL)).willReturn(Optional.of(existing));

		Cancellation response = service.requestCancellation(
			RESERVATION_UID.toString(), new PaymentRequest.Cancel("첫 요청", 100_000L), GUEST_ID);

		assertThat(response.operationId()).isEqualTo(existing.getOperationUid());
		then(inventoryService).shouldHaveNoInteractions();
		then(paymentRepository).shouldHaveNoInteractions();
		then(operationRepository).should(never()).save(any());
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void activeCancellationRejectsADifferentReasonOrAmountFingerprint() {
		Reservation reservation = reservation(ReservationStatus.CANCELLATION_PENDING, 100_000L);
		PaymentOperation existing = PaymentOperation.createCancellation(
			reservation, GUEST_ID, "payment-key", 100_000L, "첫 요청", NOW.minusSeconds(1));
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));
		given(operationRepository.findFirstByReservationIdAndOperationTypeOrderByIdDesc(
			1L, PaymentOperationType.CANCEL)).willReturn(Optional.of(existing));

		assertThatThrownBy(() -> service.requestCancellation(
			RESERVATION_UID.toString(), new PaymentRequest.Cancel("다른 요청", null), GUEST_ID))
			.isInstanceOf(kr.kro.airbob.domain.payment.exception.PaymentOperationConflictException.class);
		assertThatThrownBy(() -> service.requestCancellation(
			RESERVATION_UID.toString(), new PaymentRequest.Cancel("첫 요청", 99_999L), GUEST_ID))
			.isInstanceOf(kr.kro.airbob.domain.payment.exception.PaymentOperationConflictException.class);

		then(paymentRepository).shouldHaveNoInteractions();
		then(operationRepository).should(never()).save(any());
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void aTerminalFailedCancellationCanCreateANewOperation() {
		Reservation reservation = reservation(ReservationStatus.CANCELLATION_FAILED, 100_000L);
		Payment payment = payment(reservation, 100_000L, 100_000L, PaymentStatus.DONE);
		PaymentOperation declined = PaymentOperation.builder()
			.id(5L)
			.operationUid(UUID.randomUUID())
			.reservation(reservation)
			.requesterMemberId(GUEST_ID)
			.operationType(PaymentOperationType.CANCEL)
			.status(PaymentOperationStatus.DECLINED)
			.build();
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));
		given(operationRepository.findFirstByReservationIdAndOperationTypeOrderByIdDesc(
			1L, PaymentOperationType.CANCEL)).willReturn(Optional.of(declined));
		given(paymentRepository.findByReservationIdWithLock(1L)).willReturn(Optional.of(payment));

		Cancellation response = service.requestCancellation(
			RESERVATION_UID.toString(), new PaymentRequest.Cancel("재시도", 100_000L), GUEST_ID);

		assertThat(response.operationId()).isNotEqualTo(declined.getOperationUid());
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_PENDING);
		then(inventoryService).shouldHaveNoInteractions();
		then(operationRepository).should().save(any(PaymentOperation.class));
		then(outboxWriter).should().append(any(PaymentOperationExecutionRequestedV1.class));
	}

	@Test
	void nonOwnerCannotInspectThePaymentOrCreateAnOperation() {
		Reservation reservation = reservation(ReservationStatus.CONFIRMED, 100_000L);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));

		assertThatThrownBy(() -> service.requestCancellation(
			RESERVATION_UID.toString(), new PaymentRequest.Cancel("공격자", null), 999L))
			.isInstanceOf(PaymentAccessDeniedException.class);

		then(paymentRepository).shouldHaveNoInteractions();
		then(operationRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void paidCancellationRequiresTheFullCurrentActiveBalance() {
		Reservation reservation = reservation(ReservationStatus.CONFIRMED, 100_000L);
		Payment payment = payment(reservation, 100_000L, 100_000L, PaymentStatus.DONE);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));
		given(operationRepository.findFirstByReservationIdAndOperationTypeOrderByIdDesc(
			1L, PaymentOperationType.CANCEL)).willReturn(Optional.empty());
		given(paymentRepository.findByReservationIdWithLock(1L)).willReturn(Optional.of(payment));

		assertThatThrownBy(() -> service.requestCancellation(
			RESERVATION_UID.toString(), new PaymentRequest.Cancel("부분 환불", 50_000L), GUEST_ID))
			.isInstanceOf(InvalidInputException.class);

		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		then(operationRepository).should(never()).save(any());
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void complimentaryCancellationCompletesInTheDatabaseWithoutPaymentMessaging() {
		Reservation reservation = reservation(ReservationStatus.CONFIRMED, 0L);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));

		Cancellation response = service.requestCancellation(
			RESERVATION_UID.toString(), new PaymentRequest.Cancel("0원 예약 취소", null), GUEST_ID);

		assertThat(response.completedSynchronously()).isTrue();
		assertThat(response.operationId()).isNull();
		assertThat(response.status()).isEqualTo(Status.SUCCEEDED);
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
		InOrder lockOrder = inOrder(reservationRepository, inventoryService);
		lockOrder.verify(reservationRepository).findByReservationUidWithLock(RESERVATION_UID);
		lockOrder.verify(inventoryService).releaseOccupied(
			ACCOMMODATION_ID,
			CHECK_IN,
			CHECK_OUT,
			reservation.getId());
		then(couponUsageService).should().restore(1L);
		then(searchRefreshPublisher).should().requestRefresh(ACCOMMODATION_UID);
		then(paymentRepository).shouldHaveNoInteractions();
		then(operationRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	@Test
	void complimentaryCancellationStopsBeforeDownstreamEffectsWhenInventoryIsNotOwned() {
		Reservation reservation = reservation(ReservationStatus.CONFIRMED, 0L);
		given(reservationRepository.findByReservationUidWithLock(RESERVATION_UID))
			.willReturn(Optional.of(reservation));
		willThrow(new ReservationInventoryInvariantViolationException("not the inventory owner"))
			.given(inventoryService).releaseOccupied(
				ACCOMMODATION_ID,
				CHECK_IN,
				CHECK_OUT,
				reservation.getId());

		assertThatThrownBy(() -> service.requestCancellation(
			RESERVATION_UID.toString(),
			new PaymentRequest.Cancel("0원 예약 취소", null),
			GUEST_ID))
			.isInstanceOf(ReservationInventoryInvariantViolationException.class);

		then(couponUsageService).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
		then(searchRefreshPublisher).shouldHaveNoInteractions();
		then(paymentRepository).shouldHaveNoInteractions();
		then(operationRepository).shouldHaveNoInteractions();
		then(outboxWriter).shouldHaveNoInteractions();
	}

	private Reservation reservation(ReservationStatus status, long totalPrice) {
		return Reservation.builder()
			.id(1L)
			.reservationUid(RESERVATION_UID)
			.guest(Member.builder().id(GUEST_ID).build())
			.accommodation(Accommodation.builder()
				.id(ACCOMMODATION_ID)
				.accommodationUid(ACCOMMODATION_UID)
				.build())
			.checkInDate(CHECK_IN)
			.checkOutDate(CHECK_OUT)
			.checkInAt(NOW.plusSeconds(24 * 60 * 60))
			.totalPrice(totalPrice)
			.status(status)
			.build();
	}

	private Payment payment(
		Reservation reservation,
		long amount,
		long balanceAmount,
		PaymentStatus status
	) {
		return Payment.builder()
			.id(2L)
			.paymentKey("payment-key")
			.orderId(RESERVATION_UID.toString())
			.amount(amount)
			.balanceAmount(balanceAmount)
			.method(PaymentMethod.CARD)
			.status(status)
			.reservation(reservation)
			.build();
	}
}
