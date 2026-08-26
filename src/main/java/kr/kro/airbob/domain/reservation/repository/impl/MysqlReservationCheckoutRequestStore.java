package kr.kro.airbob.domain.reservation.repository.impl;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.reservation.exception.ReservationStateChangeException;
import kr.kro.airbob.domain.reservation.idempotency.ReservationCheckoutIdentity;
import kr.kro.airbob.domain.reservation.repository.ReservationCheckoutRequestClaim;
import kr.kro.airbob.domain.reservation.repository.ReservationCheckoutRequestStore;

@Repository
public class MysqlReservationCheckoutRequestStore implements ReservationCheckoutRequestStore {
	private static final String ENDPOINT = "RESERVATION_CHECKOUT_V1";
	private static final String INSERT_OR_LOCK = """
		INSERT INTO reservation_checkout_request (
		  member_id, endpoint, key_hash, request_fingerprint,
		  created_at, updated_at
		) VALUES (?, ?, ?, ?, ?, ?)
		ON DUPLICATE KEY UPDATE id = id
		""";
	private static final String LOCK = """
		SELECT id, request_fingerprint, reservation_id
		FROM reservation_checkout_request
		WHERE member_id = ? AND endpoint = ? AND key_hash = ?
		FOR UPDATE
		""";
	private static final String COMPLETE = """
		UPDATE reservation_checkout_request
		SET reservation_id = ?, completed_at = ?, updated_at = ?
		WHERE id = ? AND reservation_id IS NULL
		""";

	private final JdbcTemplate jdbcTemplate;

	public MysqlReservationCheckoutRequestStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public ReservationCheckoutRequestClaim lockOrCreate(
		long memberId,
		ReservationCheckoutIdentity identity,
		Instant createdAt
	) {
		Timestamp timestamp = Timestamp.from(createdAt);
		jdbcTemplate.update(
			INSERT_OR_LOCK,
			memberId,
			ENDPOINT,
			identity.keyHash(),
			identity.requestFingerprint(),
			timestamp,
			timestamp
		);
		return jdbcTemplate.queryForObject(
			LOCK,
			(rs, rowNum) -> {
				long reservationId = rs.getLong("reservation_id");
				boolean reservationIdWasNull = rs.wasNull();
				return new ReservationCheckoutRequestClaim(
					rs.getLong("id"),
					rs.getString("request_fingerprint"),
					reservationIdWasNull ? null : reservationId
				);
			},
			memberId,
			ENDPOINT,
			identity.keyHash()
		);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void complete(long checkoutRequestId, long reservationId, Instant completedAt) {
		Timestamp timestamp = Timestamp.from(completedAt);
		int updated = jdbcTemplate.update(
			COMPLETE,
			reservationId,
			timestamp,
			timestamp,
			checkoutRequestId
		);
		if (updated != 1) {
			throw new ReservationStateChangeException();
		}
	}
}
