package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminResponse.AvailableAction;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminResponse.ManualReviewQueue;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.repository.PaymentOperationManualReviewQueryRepository;
import kr.kro.airbob.domain.payment.repository.projection.PaymentOperationManualReviewQueueItem;

@ExtendWith(MockitoExtension.class)
class PaymentOperationManualReviewQueryServiceTest {

	@Mock private PaymentOperationManualReviewQueryRepository repository;

	private PaymentOperationManualReviewQueryService service;

	@BeforeEach
	void setUp() {
		service = new PaymentOperationManualReviewQueryService(repository);
	}

	@Test
	void fetchesOneExtraRowToReportHasMoreAndReturnsOnlyTheRequestedLimit() {
		PaymentOperationManualReviewQueueItem first = item(
			"98283dcc-f24f-44b2-a877-d89983fb7e31", PaymentOperationType.CONFIRM, true, 1);
		PaymentOperationManualReviewQueueItem second = item(
			"98283dcc-f24f-44b2-a877-d89983fb7e32", PaymentOperationType.CANCEL, false, 2);
		PaymentOperationManualReviewQueueItem extra = item(
			"98283dcc-f24f-44b2-a877-d89983fb7e33", PaymentOperationType.CONFIRM, false, 3);
		given(repository.findOldest(3)).willReturn(List.of(first, second, extra));

		ManualReviewQueue result = service.findManualReviewQueue(2);

		assertThat(result.hasMore()).isTrue();
		assertThat(result.items()).hasSize(2);
		assertThat(result.items().getFirst().availableActions())
			.containsExactly(AvailableAction.REQUEST_RECONCILIATION, AvailableAction.MARK_NOT_PAID);
		assertThat(result.items().get(1).availableActions())
			.containsExactly(AvailableAction.REQUEST_RECONCILIATION);
		then(repository).should().findOldest(3);
	}

	@Test
	void hidesMarkNotPaidUntilAConfirmOperationHasEligibleProviderEvidence() {
		PaymentOperationManualReviewQueueItem ineligibleConfirm = item(
			"98283dcc-f24f-44b2-a877-d89983fb7e34", PaymentOperationType.CONFIRM, false, 1);
		given(repository.findOldest(2)).willReturn(List.of(ineligibleConfirm));

		ManualReviewQueue result = service.findManualReviewQueue(1);

		assertThat(result.hasMore()).isFalse();
		assertThat(result.items().getFirst().availableActions())
			.containsExactly(AvailableAction.REQUEST_RECONCILIATION);
	}

	@Test
	void rejectsLimitsOutsideThePublicOneToOneHundredRangeWithoutQuerying() {
		assertThatThrownBy(() -> service.findManualReviewQueue(0))
			.isInstanceOf(InvalidInputException.class);
		assertThatThrownBy(() -> service.findManualReviewQueue(101))
			.isInstanceOf(InvalidInputException.class);

		then(repository).shouldHaveNoInteractions();
	}

	private static PaymentOperationManualReviewQueueItem item(
		String operationUid,
		PaymentOperationType operationType,
		boolean notPaidResolutionEligible,
		int offset
	) {
		return new PaymentOperationManualReviewQueueItem(
			UUID.fromString(operationUid),
			operationType,
			4 + offset,
			1 + offset,
			Instant.parse("2026-08-17T00:00:00Z").plusSeconds(offset),
			offset,
			notPaidResolutionEligible
		);
	}
}
