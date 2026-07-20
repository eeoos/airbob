package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;

import kr.kro.airbob.domain.coupon.exception.CouponLockTimeoutException;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;

@ExtendWith(MockitoExtension.class)
class CouponLockIssueServiceTest {

	@Mock
	private CouponIssueTransactionService transactionService;
	@Mock
	private CouponLockManager lockManager;
	@Mock
	private CouponIssueMetricRecorder metricRecorder;

	private CouponLockIssueService service;

	@BeforeEach
	void setUp() {
		service = new CouponLockIssueService(transactionService, lockManager, metricRecorder);
	}

	@Test
	@DisplayName("분산 락 안에서 DB 발급을 커밋한 뒤 락을 해제한다")
	void commitsDatabaseIssueBeforeUnlock() {
		RLock lock = mock(RLock.class);
		when(lockManager.acquireLock(1L)).thenReturn(lock);

		service.issue(1L, 10L);

		InOrder order = inOrder(lockManager, transactionService);
		order.verify(lockManager).acquireLock(1L);
		order.verify(transactionService).issueUnderLock(1L, 10L);
		order.verify(lockManager).releaseLock(lock);
		verify(metricRecorder).recordDatabase(
			eq(CouponIssueMetricRecorder.Strategy.LOCK),
			eq(CouponIssueMetricRecorder.DatabaseResult.SUCCESS),
			anyLong());
		verify(metricRecorder).recordIssue(
			eq(CouponIssueMetricRecorder.Strategy.LOCK),
			eq(CouponIssueMetricRecorder.IssueResult.SUCCESS),
			anyLong());
	}

	@Test
	@DisplayName("DB 발급이 실패해도 락을 해제하고 원래 예외를 전달한다")
	void releasesLockWhenDatabaseIssueFails() {
		RLock lock = mock(RLock.class);
		IllegalStateException databaseFailure = new IllegalStateException("db failure");
		when(lockManager.acquireLock(1L)).thenReturn(lock);
		org.mockito.Mockito.doThrow(databaseFailure)
			.when(transactionService).issueUnderLock(1L, 10L);

		assertThatThrownBy(() -> service.issue(1L, 10L)).isSameAs(databaseFailure);

		InOrder order = inOrder(lockManager, transactionService);
		order.verify(lockManager).acquireLock(1L);
		order.verify(transactionService).issueUnderLock(1L, 10L);
		order.verify(lockManager).releaseLock(lock);
		verify(metricRecorder).recordDatabase(
			eq(CouponIssueMetricRecorder.Strategy.LOCK),
			eq(CouponIssueMetricRecorder.DatabaseResult.ERROR),
			anyLong());
		verify(metricRecorder).recordIssue(
			eq(CouponIssueMetricRecorder.Strategy.LOCK),
			eq(CouponIssueMetricRecorder.IssueResult.ERROR),
			anyLong());
	}

	@Test
	@DisplayName("락 타임아웃은 DB를 호출하지 않고 별도 전체 결과로 기록한다")
	void recordsLockTimeoutSeparately() {
		CouponLockTimeoutException timeout = new CouponLockTimeoutException();
		when(lockManager.acquireLock(1L)).thenThrow(timeout);

		assertThatThrownBy(() -> service.issue(1L, 10L)).isSameAs(timeout);

		verify(transactionService, org.mockito.Mockito.never()).issueUnderLock(1L, 10L);
		verify(metricRecorder).recordIssue(
			eq(CouponIssueMetricRecorder.Strategy.LOCK),
			eq(CouponIssueMetricRecorder.IssueResult.TIMEOUT),
			anyLong());
	}
}
