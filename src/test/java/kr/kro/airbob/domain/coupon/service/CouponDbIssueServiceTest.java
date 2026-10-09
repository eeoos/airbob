package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.coupon.exception.CouponSoldOutException;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;

@ExtendWith(MockitoExtension.class)
class CouponDbIssueServiceTest {

	@Mock
	private CouponIssueTransactionService transactionService;
	@Mock
	private CouponIssueMetricRecorder metricRecorder;

	private CouponDbIssueService service;

	@BeforeEach
	void setUp() {
		service = new CouponDbIssueService(transactionService, metricRecorder);
	}

	@Test
	void recordsCommittedDatabaseIssue() {
		service.issue(1L, 10L);

		verify(transactionService).issueWithConditionalUpdate(1L, 10L);
		verifyMetrics(CouponIssueMetricRecorder.DatabaseResult.SUCCESS,
			CouponIssueMetricRecorder.IssueResult.SUCCESS);
	}

	@Test
	void propagatesDatabaseFailureAndRecordsError() {
		IllegalStateException failure = new IllegalStateException("db failure");
		doThrow(failure).when(transactionService).issueWithConditionalUpdate(1L, 10L);

		assertThatThrownBy(() -> service.issue(1L, 10L)).isSameAs(failure);

		verifyMetrics(CouponIssueMetricRecorder.DatabaseResult.ERROR,
			CouponIssueMetricRecorder.IssueResult.ERROR);
	}

	@Test
	void recordsSoldOutAsRejection() {
		CouponSoldOutException failure = new CouponSoldOutException();
		doThrow(failure).when(transactionService).issueWithConditionalUpdate(1L, 10L);

		assertThatThrownBy(() -> service.issue(1L, 10L)).isSameAs(failure);

		verifyMetrics(CouponIssueMetricRecorder.DatabaseResult.REJECTED,
			CouponIssueMetricRecorder.IssueResult.SOLD_OUT);
	}

	private void verifyMetrics(CouponIssueMetricRecorder.DatabaseResult databaseResult,
		CouponIssueMetricRecorder.IssueResult issueResult) {
		verify(metricRecorder).recordDatabase(eq(CouponIssueMetricRecorder.Strategy.DB),
			eq(databaseResult), anyLong());
		verify(metricRecorder).recordIssue(eq(CouponIssueMetricRecorder.Strategy.DB),
			eq(issueResult), anyLong());
	}
}
