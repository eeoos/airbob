package kr.kro.airbob.domain.payment.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.repository.projection.PaymentOperationManualReviewQueueItem;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewResult;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PaymentOperationAdminResponse {

	public enum AvailableAction {
		REQUEST_RECONCILIATION,
		MARK_NOT_PAID
	}

	public record ManualReviewQueue(List<ManualReviewItem> items, boolean hasMore) {
		public ManualReviewQueue {
			items = List.copyOf(items);
		}
	}

	public record ActionAccepted(UUID operationUid, String status, long version) {
		public static ActionAccepted from(PaymentOperationManualReviewResult result) {
			return new ActionAccepted(
				result.operationUid(),
				result.status().name(),
				result.version()
			);
		}
	}

	public record ManualReviewItem(
		UUID operationUid,
		PaymentOperationType operationType,
		Instant reviewRequiredAt,
		int attemptCount,
		int manualReviewCount,
		long version,
		List<AvailableAction> availableActions
	) {
		public ManualReviewItem {
			availableActions = List.copyOf(availableActions);
		}

		public static ManualReviewItem from(PaymentOperationManualReviewQueueItem item) {
			return new ManualReviewItem(
				item.operationUid(),
				item.operationType(),
				item.reviewRequiredAt(),
				item.attemptCount(),
				item.manualReviewCount(),
				item.version(),
				availableActions(item)
			);
		}

		private static List<AvailableAction> availableActions(
			PaymentOperationManualReviewQueueItem item
		) {
			if (item.operationType() == PaymentOperationType.CONFIRM
				&& item.notPaidResolutionEligible()) {
				return List.of(
					AvailableAction.REQUEST_RECONCILIATION,
					AvailableAction.MARK_NOT_PAID
				);
			}
			return List.of(AvailableAction.REQUEST_RECONCILIATION);
		}
	}
}
