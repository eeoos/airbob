package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.coupon.exception.CouponAlreadyIssuedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotIssuableException;
import kr.kro.airbob.domain.coupon.exception.CouponSoldOutException;
import kr.kro.airbob.domain.coupon.exception.CouponStockNotPreparedException;

@ExtendWith(MockitoExtension.class)
class CouponLuaIssueServiceTest {

	@Mock
	private CouponRedisStockManager stockManager;
	@Mock
	private CouponIssueTransactionService transactionService;

	private CouponLuaIssueService service;

	@BeforeEach
	void setUp() {
		service = new CouponLuaIssueService(stockManager, transactionService);
	}

	@Test
	@DisplayName("Lua 승인 요청만 DB에 발급 완료 상태로 저장한다")
	void persistsApprovedIssue() {
		when(stockManager.issue(1L, 10L)).thenReturn(CouponRedisIssueResult.approved(9));

		service.issue(1L, 10L);

		verify(transactionService).persistApprovedIssue(1L, 10L);
	}

	@ParameterizedTest(name = "{0} 결과를 {1} 예외로 변환한다")
	@MethodSource("rejectedResults")
	void mapsRejectedLuaResult(
		CouponRedisIssueStatus status,
		Class<? extends RuntimeException> exceptionType
	) {
		when(stockManager.issue(1L, 10L))
			.thenReturn(CouponRedisIssueResult.rejected(status));

		assertThatThrownBy(() -> service.issue(1L, 10L)).isInstanceOf(exceptionType);
		verify(transactionService, never()).persistApprovedIssue(1L, 10L);
	}

	@Test
	@DisplayName("DB 저장 실패 시 Redis 승인을 보상하고 원래 예외를 전달한다")
	void compensatesRedisWhenDatabasePersistenceFails() {
		IllegalStateException databaseFailure = new IllegalStateException("db failure");
		when(stockManager.issue(1L, 10L)).thenReturn(CouponRedisIssueResult.approved(9));
		org.mockito.Mockito.doThrow(databaseFailure)
			.when(transactionService).persistApprovedIssue(1L, 10L);
		when(stockManager.compensate(1L, 10L))
			.thenReturn(CouponRedisCompensationResult.COMPENSATED);

		assertThatThrownBy(() -> service.issue(1L, 10L)).isSameAs(databaseFailure);
		verify(stockManager).compensate(1L, 10L);
	}

	@Test
	@DisplayName("Redis 보상까지 실패해도 원래 DB 예외를 가리지 않는다")
	void preservesDatabaseFailureWhenCompensationAlsoFails() {
		IllegalStateException databaseFailure = new IllegalStateException("db failure");
		IllegalStateException compensationFailure = new IllegalStateException("redis failure");
		when(stockManager.issue(1L, 10L)).thenReturn(CouponRedisIssueResult.approved(9));
		org.mockito.Mockito.doThrow(databaseFailure)
			.when(transactionService).persistApprovedIssue(1L, 10L);
		when(stockManager.compensate(1L, 10L)).thenThrow(compensationFailure);

		assertThatThrownBy(() -> service.issue(1L, 10L))
			.isSameAs(databaseFailure)
			.satisfies(error -> assertThat(error.getSuppressed()).containsExactly(compensationFailure));
	}

	private static Stream<Arguments> rejectedResults() {
		return Stream.of(
			Arguments.of(CouponRedisIssueStatus.SOLD_OUT, CouponSoldOutException.class),
			Arguments.of(CouponRedisIssueStatus.DUPLICATE, CouponAlreadyIssuedException.class),
			Arguments.of(CouponRedisIssueStatus.NOT_STARTED, CouponNotIssuableException.class),
			Arguments.of(CouponRedisIssueStatus.ENDED, CouponNotIssuableException.class),
			Arguments.of(CouponRedisIssueStatus.INACTIVE, CouponNotIssuableException.class),
			Arguments.of(CouponRedisIssueStatus.UNPREPARED, CouponStockNotPreparedException.class));
	}
}
