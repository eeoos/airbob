package kr.kro.airbob.domain.payment.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
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

	@Schema(name = "PaymentOperationAdminResponseManualReviewQueue")
	public record ManualReviewQueue(
		@Schema(description = "수동 확인 진입 시각·내부 ID 오름차순의 작업. 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
		List<ManualReviewItem> items,
		@Schema(description = "limit을 넘는 대기 작업 존재 여부. 다음 페이지 커서는 제공하지 않습니다.", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean hasMore
	) {
		public ManualReviewQueue {
			items = List.copyOf(items);
		}
	}

	@Schema(name = "PaymentOperationAdminResponseActionAccepted")
	public record ActionAccepted(
		@Schema(description = "관리 작업 대상 UUID", example = "00000000-0000-0000-0000-000000000040", requiredMode = Schema.RequiredMode.REQUIRED)
		UUID operationUid,
		@Schema(description = "관리자용 작업 상태. 대사 접수는 QUEUED, 미결제 확정 반영은 DECLINED입니다.", allowableValues = {"QUEUED", "DECLINED"}, example = "QUEUED", requiredMode = Schema.RequiredMode.REQUIRED)
		String status,
		@Schema(description = "요청 반영 직후의 버전. 후속 처리로 다시 바뀔 수 있습니다.", minimum = "0", example = "8", requiredMode = Schema.RequiredMode.REQUIRED)
		long version
	) {
		public static ActionAccepted from(PaymentOperationManualReviewResult result) {
			return new ActionAccepted(
				result.operationUid(),
				result.status().name(),
				result.version()
			);
		}
	}

	@Schema(name = "PaymentOperationAdminResponseManualReviewItem")
	public record ManualReviewItem(
		@Schema(description = "관리 API 경로의 operationId로 사용할 UUID", example = "00000000-0000-0000-0000-000000000040", requiredMode = Schema.RequiredMode.REQUIRED)
		UUID operationUid,
		@Schema(description = "승인(CONFIRM) 또는 취소(CANCEL) 작업", example = "CONFIRM", requiredMode = Schema.RequiredMode.REQUIRED)
		PaymentOperationType operationType,
		@Schema(description = "가장 최근 수동 확인 상태에 진입한 UTC 시각", example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant reviewRequiredAt,
		@Schema(description = "현재 처리 주기의 시도 횟수. 관리자 대사 요청 시 0으로 초기화됩니다.", minimum = "0", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
		int attemptCount,
		@Schema(description = "수동 확인 상태에 진입한 누적 횟수", minimum = "0", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		int manualReviewCount,
		@Schema(description = "관리 요청의 expected_version으로 사용할 버전", minimum = "0", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
		long version,
		@Schema(description = "현재 허용된 작업. MARK_NOT_PAID는 미결제 확정 자격이 확인된 CONFIRM 작업에만 포함됩니다.", requiredMode = Schema.RequiredMode.REQUIRED)
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
