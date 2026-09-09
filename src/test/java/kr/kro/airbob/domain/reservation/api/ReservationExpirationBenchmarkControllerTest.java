package kr.kro.airbob.domain.reservation.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkAccessGuard;
import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkDatabaseGuard;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.reservation.api.ReservationExpirationBenchmarkController.Request;
import kr.kro.airbob.domain.reservation.api.ReservationExpirationBenchmarkController.Variant;
import kr.kro.airbob.domain.reservation.service.ExpiredReservationCleanupService;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBeforeBenchmarkService;

class ReservationExpirationBenchmarkControllerTest {

	private final BulkWriteBenchmarkAccessGuard access = mock(BulkWriteBenchmarkAccessGuard.class);
	private final BulkWriteBenchmarkDatabaseGuard database = mock(BulkWriteBenchmarkDatabaseGuard.class);
	private final ExpiredReservationCleanupService after = mock(ExpiredReservationCleanupService.class);
	private final ReservationHistoryInsertBeforeBenchmarkService before = mock(ReservationHistoryInsertBeforeBenchmarkService.class);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);
	private final ReservationExpirationBenchmarkController controller =
		new ReservationExpirationBenchmarkController(access, database, after, before, jdbc, clock);

	@BeforeEach
	void context() {
		UserContext.set(new UserInfo(123L));
	}

	@AfterEach
	void clearContext() {
		UserContext.clear();
	}

	@Test
	void refusesBadAccessBeforeReadingTheDatabase() {
		doThrow(new IllegalStateException("denied")).when(access).verify("bad");
		assertThatThrownBy(() -> controller.execute(new Request(Variant.AFTER), "bad"))
			.isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(database, jdbc, after, before);
	}

	@Test
	void refusesUnqualifiedDatabaseBeforeAnyDomainWork() {
		doThrow(new IllegalStateException("database")).when(database).verifyReady();
		assertThatThrownBy(() -> controller.execute(new Request(Variant.AFTER), "valid"))
			.isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(jdbc, after, before);
	}

	@Test
	void refusesMissingVariantAndMoreThan32Rows() {
		assertThatThrownBy(() -> controller.execute(new Request(null), "valid"))
			.isInstanceOf(IllegalArgumentException.class);
		when(jdbc.queryForObject(anyString(), eq(Integer.class), any(LocalDateTime.class))).thenReturn(33);
		assertThatThrownBy(() -> controller.execute(new Request(Variant.BEFORE), "valid"))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(after, before);
	}

	@Test
	void runsAfterAsSystemAndRestoresContextOnSuccess() {
		when(jdbc.queryForObject(anyString(), eq(Integer.class), any(LocalDateTime.class))).thenReturn(8);
		when(after.cleanupExpiredPendingReservations()).thenAnswer(invocation -> {
			assertThat(UserContext.get()).isNull();
			return 8;
		});
		assertThat(controller.execute(new Request(Variant.AFTER), "valid")).containsEntry("processed", 8);
		assertThat(UserContext.get().id()).isEqualTo(123L);
		verifyNoInteractions(before);
	}

	@Test
	void restoresContextWhenBeforeFails() {
		when(jdbc.queryForObject(anyString(), eq(Integer.class), any(LocalDateTime.class))).thenReturn(8);
		doAnswer(invocation -> {
			assertThat(UserContext.get()).isNull();
			throw new IllegalStateException("intentional failure");
		}).when(before).cleanupExpiredPendingReservations();
		assertThatThrownBy(() -> controller.execute(new Request(Variant.BEFORE), "valid"))
			.isInstanceOf(IllegalStateException.class);
		assertThat(UserContext.get().id()).isEqualTo(123L);
		verifyNoInteractions(after);
	}
}
