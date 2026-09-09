package kr.kro.airbob.domain.reservation.api;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkAccessGuard;
import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkDatabaseGuard;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.domain.reservation.service.ExpiredReservationCleanupService;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBeforeBenchmarkService;
import lombok.RequiredArgsConstructor;

/** Runs domain cleanup on an externally prepared, disposable small fixture. Creates no ETL data. */
@RestController
@RequiredArgsConstructor
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "external-fixture-enabled", havingValue = "true")
@RequestMapping("/api/v2/admin/benchmarks/bulk-write/reservation-expiration")
public class ReservationExpirationBenchmarkController {

	private final BulkWriteBenchmarkAccessGuard accessGuard;
	private final BulkWriteBenchmarkDatabaseGuard databaseGuard;
	private final ExpiredReservationCleanupService after;
	private final ReservationHistoryInsertBeforeBenchmarkService before;
	private final JdbcTemplate jdbc;
	private final Clock clock;

	public enum Variant { BEFORE, AFTER }
	public record Request(Variant variant) {}

	@PostMapping("/execute")
	public Map<String, Object> execute(
		@RequestBody Request request,
		@RequestHeader(value = BulkWriteBenchmarkAccessGuard.HEADER_NAME, required = false) String token
	) {
		accessGuard.verify(token);
		databaseGuard.verifyReady();
		if (request == null || request.variant() == null) {
			throw new IllegalArgumentException("variant required");
		}
		int eligible = jdbc.queryForObject(
			"SELECT COUNT(*) FROM reservation WHERE status='PAYMENT_PENDING' AND expires_at<=?",
			Integer.class, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
		if (eligible > 32) {
			throw new IllegalArgumentException("External qualification fixture is limited to 32 expired reservations");
		}
		var previous = UserContext.get();
		UserContext.clear();
		try {
			int processed;
			if (request.variant() == Variant.BEFORE) {
				before.cleanupExpiredPendingReservations();
				processed = eligible;
			} else {
				processed = after.cleanupExpiredPendingReservations();
			}
			return Map.of("processed", processed, "variant", request.variant().name());
		} finally {
			UserContext.clear();
			if (previous != null) {
				UserContext.set(previous);
			}
		}
	}
}
