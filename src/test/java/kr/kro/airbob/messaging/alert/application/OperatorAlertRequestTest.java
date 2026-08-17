package kr.kro.airbob.messaging.alert.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import kr.kro.airbob.messaging.alert.event.OperatorAlertKind;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSummaryCode;

class OperatorAlertRequestTest {

	private static final UUID OPERATION_UID =
		UUID.fromString("53f2e8da-eb31-40f3-a768-6827048c347c");
	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("46b24f9f-5d74-4ca2-af06-e1f49766276a");

	@Test
	void manualReviewOccurrenceIsStablePerOperationAndReviewCycle() {
		OperatorAlertRequest first = OperatorAlertRequest.paymentManualReview(OPERATION_UID, 1);
		OperatorAlertRequest duplicate = OperatorAlertRequest.paymentManualReview(OPERATION_UID, 1);
		OperatorAlertRequest nextCycle = OperatorAlertRequest.paymentManualReview(OPERATION_UID, 2);

		assertThat(first)
			.isEqualTo(duplicate)
			.extracting(
				OperatorAlertRequest::kind,
				OperatorAlertRequest::subjectUid,
				OperatorAlertRequest::summaryCode,
				OperatorAlertRequest::sourcePosition)
			.containsExactly(
				OperatorAlertKind.PAYMENT_MANUAL_REVIEW,
				OPERATION_UID,
				OperatorAlertSummaryCode.PROVIDER_RESULT_UNKNOWN,
				OperatorAlertSourcePosition.none());
		assertThat(nextCycle.occurrenceUid()).isNotEqualTo(first.occurrenceUid());
	}

	@Test
	void quarantineOccurrenceIsTheCanonicalCoordinateAndPoisonSubjectIsDeterministic() {
		OperatorAlertSourcePosition source =
			new OperatorAlertSourcePosition("PAYMENT_OPERATION.events", 2, 41L);

		OperatorAlertRequest valid =
			OperatorAlertRequest.paymentOperationQuarantined(OPERATION_UID, source);
		OperatorAlertRequest poison =
			OperatorAlertRequest.paymentOperationQuarantined(null, source);
		OperatorAlertRequest poisonDuplicate =
			OperatorAlertRequest.paymentOperationQuarantined(null, source);

		assertThat(valid.kind()).isEqualTo(OperatorAlertKind.PAYMENT_OPERATION_QUARANTINED);
		assertThat(valid.subjectUid()).isEqualTo(OPERATION_UID);
		assertThat(valid.summaryCode()).isEqualTo(OperatorAlertSummaryCode.MESSAGE_PROCESSING_FAILED);
		assertThat(valid.sourcePosition()).isEqualTo(source);
		assertThat(poison).isEqualTo(poisonDuplicate);
		assertThat(poison.subjectUid()).isEqualTo(poison.occurrenceUid());
		assertThat(poison.occurrenceUid()).isEqualTo(valid.occurrenceUid());
	}

	@Test
	void searchQuarantineUsesTheAccommodationWhenThePayloadIsValid() {
		OperatorAlertSourcePosition source =
			new OperatorAlertSourcePosition("ACCOMMODATION_INDEX.events", 0, 7L);

		OperatorAlertRequest request =
			OperatorAlertRequest.accommodationIndexQuarantined(ACCOMMODATION_UID, source);

		assertThat(request.kind()).isEqualTo(OperatorAlertKind.ACCOMMODATION_INDEX_QUARANTINED);
		assertThat(request.subjectUid()).isEqualTo(ACCOMMODATION_UID);
		assertThat(request.summaryCode()).isEqualTo(OperatorAlertSummaryCode.INDEX_REFRESH_FAILED);
		assertThat(request.sourcePosition()).isEqualTo(source);
	}
}
