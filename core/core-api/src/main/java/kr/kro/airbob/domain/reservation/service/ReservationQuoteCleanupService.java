package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;


@Service
public class ReservationQuoteCleanupService {

	private final ReservationQuoteWriter quoteWriter;
	private final Clock clock;
	private final Duration retention;
	private final int batchSize;

	public ReservationQuoteCleanupService(
		ReservationQuoteWriter quoteWriter,
		Clock clock,
		@Value("${reservation.quote.retention:30d}") Duration retention,
		@Value("${reservation.quote.cleanup-batch-size:500}") int batchSize
	) {
		this.quoteWriter = Objects.requireNonNull(quoteWriter);
		this.clock = Objects.requireNonNull(clock);
		if (retention == null || retention.isZero() || retention.isNegative()) {
			throw new IllegalArgumentException("quote retention must be positive");
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("quote cleanup batch size must be positive");
		}
		this.retention = retention;
		this.batchSize = batchSize;
	}

	@Transactional(
		isolation = Isolation.READ_COMMITTED,
		timeoutString = "${reservation.quote.cleanup-transaction-timeout-seconds:10}"
	)
	public int cleanupOneBatch() {
		Instant cutoffExclusive = clock.instant().minus(retention);
		return quoteWriter.deleteRetentionExpiredBatch(cutoffExclusive, batchSize);
	}
}
