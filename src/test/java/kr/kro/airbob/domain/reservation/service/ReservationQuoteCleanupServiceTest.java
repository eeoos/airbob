package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.reservation.policy.ReservationQuotePolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationQuoteRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationQuoteCleanupService 테스트")
class ReservationQuoteCleanupServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
	private static final Duration RETENTION = Duration.ofDays(30);
	private static final Instant RETENTION_CUTOFF = NOW.minus(RETENTION);
	private static final int BATCH_SIZE = 100;

	@Mock
	private ReservationQuoteRepository quoteRepository;

	private ReservationQuoteCleanupService service;

	@BeforeEach
	void setUp() {
		service = new ReservationQuoteCleanupService(
			quoteRepository,
			Clock.fixed(NOW, ZoneOffset.UTC),
			ReservationQuotePolicy.defaultPolicy(),
			RETENTION,
			BATCH_SIZE
		);
	}

	@Test
	@DisplayName("30일 보존 경계보다 오래된 quote를 생성 시각 순 batch로 조회해 한 번에 삭제한다")
	void deletesOneBoundedBatchUsingTheRetentionCutoff() {
		given(quoteRepository.findExpiredIdsForCleanup(RETENTION_CUTOFF, BATCH_SIZE))
			.willReturn(List.of(11L, 12L));
		given(quoteRepository.deleteCleanupBatchByIds(List.of(11L, 12L))).willReturn(2);

		int deleted = service.cleanupOneBatch();

		assertThat(deleted).isEqualTo(2);
		then(quoteRepository).should().findExpiredIdsForCleanup(RETENTION_CUTOFF, BATCH_SIZE);
		then(quoteRepository).should().deleteCleanupBatchByIds(List.of(11L, 12L));
		then(quoteRepository).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("보존 기간을 지난 quote가 없으면 delete를 실행하지 않는다")
	void skipsDeleteForAnEmptyBatch() {
		given(quoteRepository.findExpiredIdsForCleanup(RETENTION_CUTOFF, BATCH_SIZE))
			.willReturn(List.of());

		int deleted = service.cleanupOneBatch();

		assertThat(deleted).isZero();
		then(quoteRepository).should().findExpiredIdsForCleanup(RETENTION_CUTOFF, BATCH_SIZE);
		then(quoteRepository).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("한 cleanup 호출의 잠금 조회와 삭제는 하나의 트랜잭션 경계에 있다")
	void cleanupOneBatchDeclaresATransactionBoundary() throws NoSuchMethodException {
		Method cleanupMethod = ReservationQuoteCleanupService.class.getMethod("cleanupOneBatch");

		Transactional methodTransaction = AnnotatedElementUtils.findMergedAnnotation(
			cleanupMethod, Transactional.class);
		Transactional typeTransaction = AnnotatedElementUtils.findMergedAnnotation(
			ReservationQuoteCleanupService.class, Transactional.class);

		assertThat(methodTransaction != null || typeTransaction != null).isTrue();
	}

	@Test
	@DisplayName("보존 기간은 quote 자체의 유효기간보다 짧을 수 없다")
	void rejectsRetentionShorterThanQuoteDuration() {
		assertThatThrownBy(() -> new ReservationQuoteCleanupService(
			quoteRepository,
			Clock.fixed(NOW, ZoneOffset.UTC),
			ReservationQuotePolicy.defaultPolicy(),
			Duration.ofMinutes(4),
			BATCH_SIZE
		)).isInstanceOf(IllegalArgumentException.class);
	}
}
