package kr.kro.airbob.domain.payment.monitoring;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionAction;

@Repository
public class JdbcPaymentOperationHealthSnapshotRepository
	implements PaymentOperationHealthSnapshotRepository {

	static final String OPERATION_HEALTH_SQL = """
		SELECT
		  COUNT(CASE WHEN po.status = 'MANUAL_REVIEW' THEN 1 END) AS manual_review_count,
		  MIN(CASE WHEN po.status = 'MANUAL_REVIEW' THEN po.review_required_at END)
		    AS oldest_manual_review_at,
		  COUNT(CASE WHEN po.manual_reconciliation_pending = true THEN 1 END)
		    AS reconciliation_pending_count,
		  MIN(CASE WHEN po.manual_reconciliation_pending = true THEN COALESCE(
		    (
		      SELECT MAX(por.created_at)
		      FROM payment_operation_resolution por
		      WHERE por.payment_operation_id = po.id
		        AND por.resolution_action = 'RECONCILIATION_REQUESTED'
		    ),
		    po.queued_at
		  ) END) AS oldest_reconciliation_pending_at,
		  COUNT(CASE WHEN po.status = 'QUEUED' AND po.queued_at <= ? THEN 1 END)
		    AS stale_queued_count,
		  MIN(CASE WHEN po.status = 'QUEUED' AND po.queued_at <= ? THEN po.queued_at END)
		    AS oldest_stale_queued_at,
		  COUNT(CASE WHEN po.status = 'EXECUTING' AND po.lease_expires_at <= ? THEN 1 END)
		    AS expired_executing_lease_count
		FROM payment_operation po
		""";

	static final String RESOLUTION_COUNTS_SQL = """
		SELECT resolution_action, COUNT(*) AS resolution_count
		FROM payment_operation_resolution
		GROUP BY resolution_action
		""";

	private final JdbcTemplate jdbcTemplate;

	public JdbcPaymentOperationHealthSnapshotRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional(readOnly = true)
	public PaymentOperationHealthSnapshot readSnapshot(
		Instant observedAt,
		Duration staleQueuedAfter
	) {
		Objects.requireNonNull(observedAt, "observedAt must not be null");
		Objects.requireNonNull(staleQueuedAfter, "staleQueuedAfter must not be null");
		if (staleQueuedAfter.isZero() || staleQueuedAfter.isNegative()) {
			throw new IllegalArgumentException("staleQueuedAfter must be positive");
		}

		Timestamp staleCutoff = Timestamp.from(observedAt.minus(staleQueuedAfter));
		OperationBacklogs backlogs = jdbcTemplate.queryForObject(
			OPERATION_HEALTH_SQL,
			(resultSet, rowNumber) -> new OperationBacklogs(
				resultSet.getLong("manual_review_count"),
				optionalInstant(resultSet.getTimestamp("oldest_manual_review_at")),
				resultSet.getLong("reconciliation_pending_count"),
				optionalInstant(resultSet.getTimestamp("oldest_reconciliation_pending_at")),
				resultSet.getLong("stale_queued_count"),
				optionalInstant(resultSet.getTimestamp("oldest_stale_queued_at")),
				resultSet.getLong("expired_executing_lease_count")
			),
			staleCutoff,
			staleCutoff,
			Timestamp.from(observedAt)
		);

		Map<PaymentOperationResolutionAction, Long> resolutionCounts =
			new EnumMap<>(PaymentOperationResolutionAction.class);
		jdbcTemplate.query(RESOLUTION_COUNTS_SQL, resultSet -> {
			PaymentOperationResolutionAction action = parseResolutionAction(
				resultSet.getString("resolution_action"));
			resolutionCounts.put(action, resultSet.getLong("resolution_count"));
		});

		return new PaymentOperationHealthSnapshot(
			observedAt,
			backlogs.manualReviewCount(),
			backlogs.oldestManualReviewAt(),
			backlogs.reconciliationPendingCount(),
			backlogs.oldestReconciliationPendingAt(),
			backlogs.staleQueuedCount(),
			backlogs.oldestStaleQueuedAt(),
			backlogs.expiredExecutingLeaseCount(),
			resolutionCounts
		);
	}

	private static PaymentOperationResolutionAction parseResolutionAction(String value) {
		try {
			return PaymentOperationResolutionAction.valueOf(value);
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw new IllegalStateException("unknown payment operation resolution action");
		}
	}

	private static Optional<Instant> optionalInstant(Timestamp timestamp) {
		return Optional.ofNullable(timestamp).map(Timestamp::toInstant);
	}

	private record OperationBacklogs(
		long manualReviewCount,
		Optional<Instant> oldestManualReviewAt,
		long reconciliationPendingCount,
		Optional<Instant> oldestReconciliationPendingAt,
		long staleQueuedCount,
		Optional<Instant> oldestStaleQueuedAt,
		long expiredExecutingLeaseCount
	) {
	}
}
