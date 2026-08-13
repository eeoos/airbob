package kr.kro.airbob.domain.settlement.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.PlatformTransactionManager;

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
}
