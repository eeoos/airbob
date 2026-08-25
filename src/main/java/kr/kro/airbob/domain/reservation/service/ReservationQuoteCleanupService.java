package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.reservation.policy.ReservationQuotePolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationQuoteRepository;

@Service
public class ReservationQuoteCleanupService {

	private final ReservationQuoteRepository quoteRepository;
	private final Clock clock;
	private final Duration retention;
	private final int batchSize;

	public ReservationQuoteCleanupService(
		ReservationQuoteRepository quoteRepository,
		Clock clock,
		ReservationQuotePolicy quotePolicy,
		@Value("${reservation.quote.retention:30d}") Duration retention,
		@Value("${reservation.quote.cleanup-batch-size:500}") int batchSize
	) {
		this.quoteRepository = Objects.requireNonNull(quoteRepository);
		this.clock = Objects.requireNonNull(clock);
		if (retention == null || retention.compareTo(quotePolicy.ttl()) < 0) {
			throw new IllegalArgumentException(
				"quote retention must not be shorter than the quote duration");
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
		List<Long> ids = quoteRepository.findExpiredIdsForCleanup(
			cutoffExclusive, batchSize);
		if (ids.isEmpty()) {
			return 0;
		}

		return quoteRepository.deleteCleanupBatchByIds(ids);
	}
}
