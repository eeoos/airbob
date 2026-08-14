package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Detail;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Status;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;

@ExtendWith(MockitoExtension.class)
class PaymentOperationQueryServiceTest {

	private static final UUID OPERATION_UID = UUID.fromString("08a051de-b1ea-40ce-bb30-b39f2c9ba094");
	private static final Long OWNER_ID = 10L;

	@Mock private PaymentOperationRepository repository;

	@Test
	void ownerSeesPublicStatusAndNoPaymentKey() {
		PaymentOperation operation = operation(PaymentOperationStatus.DECLINED, "PROVIDER_DECLINED");
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.of(operation));

		Detail detail = new PaymentOperationQueryService(repository).find(OPERATION_UID, OWNER_ID);

		assertThat(detail.operationId()).isEqualTo(OPERATION_UID);
		assertThat(detail.status()).isEqualTo(Status.FAILED);
		assertThat(detail.failureCode()).isEqualTo("PROVIDER_DECLINED");
		assertThat(detail.updatedAt()).isEqualTo(Instant.parse("2026-08-14T01:02:03Z"));
	}

	@Test
	void nonOwnerCannotObserveAnOperation() {
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.of(operation(PaymentOperationStatus.READY, null)));

		assertThatThrownBy(() -> new PaymentOperationQueryService(repository).find(OPERATION_UID, 999L))
			.isInstanceOf(PaymentAccessDeniedException.class);
	}

	@Test
	void unknownOperationReturnsNotFound() {
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> new PaymentOperationQueryService(repository).find(OPERATION_UID, OWNER_ID))
			.isInstanceOf(PaymentOperationNotFoundException.class);
	}

	@Test
	void nonFailureStatusDoesNotExposeFailureCode() {
		PaymentOperation operation = operation(PaymentOperationStatus.RETRY_WAIT, "transient-internal-detail");
		given(repository.findByOperationUid(OPERATION_UID)).willReturn(Optional.of(operation));

		Detail detail = new PaymentOperationQueryService(repository).find(OPERATION_UID, OWNER_ID);

		assertThat(detail.status()).isEqualTo(Status.PENDING);
		assertThat(detail.failureCode()).isNull();
	}

	private PaymentOperation operation(PaymentOperationStatus status, String failureCode) {
		return PaymentOperation.builder()
			.id(1L).operationUid(OPERATION_UID).requesterMemberId(OWNER_ID).status(status)
			.reservation(Reservation.builder().reservationUid(UUID.fromString("6df13da6-735a-4a4a-a8bc-3b8acbdac9bf")).build())
			.paymentKey("never-expose-me").failureCode(failureCode)
			.updatedAt(LocalDateTime.ofInstant(Instant.parse("2026-08-14T01:02:03Z"), ZoneOffset.UTC))
			.build();
	}
}
