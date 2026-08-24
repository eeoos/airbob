package kr.kro.airbob.domain.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import kr.kro.airbob.domain.settlement.entity.Settlement;
import kr.kro.airbob.domain.settlement.exception.SettlementMonthNotClosedException;
import kr.kro.airbob.domain.settlement.repository.SettlementHistoryRepository;
import kr.kro.airbob.domain.settlement.repository.SettlementRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 시간 정책 테스트")
class SettlementServiceTest {

	private static final Instant NOW = Instant.parse("2099-01-01T00:30:00Z");

	@Mock private SettlementRepository settlementRepository;
	@Mock private SettlementHistoryRepository settlementHistoryRepository;
	@Mock private RedissonClient redissonClient;
	@Mock private PlatformTransactionManager transactionManager;
	@Mock private RLock lock;
	@Mock private TransactionStatus transactionStatus;

	private SettlementService settlementService;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		settlementService = new SettlementService(
			settlementRepository,
			settlementHistoryRepository,
			redissonClient,
			transactionManager,
			clock
		);
	}

	@Test
	@DisplayName("UTC 월 경계에서 종료된 전월 정산을 현재 Instant로 지급 처리한다")
	void marksPreviousMonthPaidAtCurrentInstant() {
		Settlement settlement = Settlement.createPending(
			1L,
			LocalDate.of(2098, 12, 1),
			100_000L,
			0L,
			new BigDecimal("0.03")
		);
		when(settlementRepository.findById(1L)).thenReturn(Optional.of(settlement));

		settlementService.markPaid(1L);

		assertThat(settlement.isPaid()).isTrue();
		assertThat(settlement.getSettledAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("UTC 기준 현재 월 정산은 지급 처리하지 않는다")
	void rejectsCurrentUtcMonth() {
		Settlement settlement = Settlement.createPending(
			1L,
			LocalDate.of(2099, 1, 1),
			100_000L,
			0L,
			new BigDecimal("0.03")
		);
		when(settlementRepository.findById(1L)).thenReturn(Optional.of(settlement));

		assertThatThrownBy(() -> settlementService.markPaid(1L))
			.isInstanceOf(SettlementMonthNotClosedException.class);
		assertThat(settlement.isPaid()).isFalse();
		assertThat(settlement.getSettledAt()).isNull();
	}

	@Test
	@DisplayName("획득한 월 정산 락은 별도 소유권 조회 없이 직접 해제한다")
	void releasesGenerationLockWithoutOwnershipProbe() throws InterruptedException {
		YearMonth month = YearMonth.of(2098, 12);
		givenAcquiredGenerationLock(month);

		settlementService.generateMonth(month);

		verify(lock).unlock();
		verify(lock, never()).isHeldByCurrentThread();
	}

	@Test
	@DisplayName("정산 실패는 후속 락 해제 실패로 덮어쓰지 않는다")
	void preservesGenerationFailureWhenUnlockAlsoFails() throws InterruptedException {
		YearMonth month = YearMonth.of(2098, 12);
		IllegalStateException generationFailure = new IllegalStateException("generation failed");
		givenAcquiredGenerationLock(month);
		when(settlementRepository.aggregateByHostForMonth(month.atDay(1), month.atEndOfMonth()))
			.thenThrow(generationFailure);
		doThrow(new IllegalStateException("unlock failed")).when(lock).unlock();

		assertThatThrownBy(() -> settlementService.generateMonth(month))
			.isSameAs(generationFailure);

		verify(lock).unlock();
		verify(lock, never()).isHeldByCurrentThread();
	}

	@Test
	@DisplayName("월 정산 락을 얻지 못하면 해제를 시도하지 않는다")
	void doesNotReleaseGenerationLockWhenAcquisitionTimesOut() throws InterruptedException {
		YearMonth month = YearMonth.of(2098, 12);
		when(redissonClient.getLock("LOCK:SETTLEMENT:GENERATE:" + month)).thenReturn(lock);
		when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(false);

		settlementService.generateMonth(month);

		verify(lock, never()).unlock();
		verify(lock, never()).isHeldByCurrentThread();
	}

	private void givenAcquiredGenerationLock(YearMonth month) throws InterruptedException {
		when(redissonClient.getLock("LOCK:SETTLEMENT:GENERATE:" + month)).thenReturn(lock);
		when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
		when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
		when(settlementRepository.aggregateByHostForMonth(month.atDay(1), month.atEndOfMonth()))
			.thenReturn(List.of());
	}
}
