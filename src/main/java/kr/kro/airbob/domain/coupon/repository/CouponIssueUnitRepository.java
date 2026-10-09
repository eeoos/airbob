package kr.kro.airbob.domain.coupon.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import kr.kro.airbob.domain.coupon.common.CouponIssueStrategy;
import kr.kro.airbob.domain.coupon.exception.CouponNotFoundException;
import lombok.RequiredArgsConstructor;

/** Native issuance SQL runs through Hibernate so the existing per-request SQL meter includes it. */
@Repository
@RequiredArgsConstructor
public class CouponIssueUnitRepository {

	private final EntityManager entityManager;
	private final JdbcTemplate jdbcTemplate;

	public record Unit(long key, int number) { }

	public void clearPersistenceContext() {
		entityManager.clear();
	}

	public LocalDateTime databaseNow() {
		// Coupon DATETIME values use Asia/Seoul. UTC_TIMESTAMP is independent of session/server time zones.
		return ((LocalDateTime)entityManager.createNativeQuery("select utc_timestamp(6)", LocalDateTime.class)
			.getSingleResult()).plusHours(9);
	}

	public CouponIssueStrategy strategy(long couponId) {
		List<?> rows = entityManager.createNativeQuery("select issue_strategy from coupon where id = :id")
			.setParameter("id", couponId).getResultList();
		if (rows.isEmpty()) throw new CouponNotFoundException();
		return CouponIssueStrategy.valueOf(rows.getFirst().toString());
	}

	public Optional<Unit> lockAvailable(long couponId, CouponIssueStrategy strategy) {
		String columns = strategy.composite() ? "unit_no, unit_no" : "id, unit_no";
		List<?> rows = entityManager.createNativeQuery("select " + columns + " from " + strategy.unitTable()
			+ " where coupon_id = :id order by unit_no limit 1 for update skip locked")
			.setParameter("id", couponId).getResultList();
		if (rows.isEmpty()) return Optional.empty();
		Object[] row = (Object[])rows.getFirst();
		return Optional.of(new Unit(((Number)row[0]).longValue(), ((Number)row[1]).intValue()));
	}

	public void consume(long couponId, CouponIssueStrategy strategy, Unit unit) {
		String key = strategy.composite() ? "unit_no" : "id";
		int changed = entityManager.createNativeQuery("delete from " + strategy.unitTable()
			+ " where coupon_id = :coupon and " + key + " = :key")
			.setParameter("coupon", couponId).setParameter("key", unit.key()).executeUpdate();
		if (changed != 1) throw new IllegalStateException("확보한 쿠폰 재고를 소비하지 못했습니다.");
	}

	public boolean hasRemaining(long couponId, CouponIssueStrategy strategy) {
		return !entityManager.createNativeQuery("select unit_no from " + strategy.unitTable()
			+ " where coupon_id = :id limit 1").setParameter("id", couponId).getResultList().isEmpty();
	}

	public long count(long couponId, CouponIssueStrategy strategy) {
		return ((Number)entityManager.createNativeQuery("select count(*) from " + strategy.unitTable()
			+ " where coupon_id = :id").setParameter("id", couponId).getSingleResult()).longValue();
	}

	public void insertRange(long couponId, CouponIssueStrategy strategy, int first, int last) {
		// Bounded multi-row statements avoid one network round trip per prepared unit.
		for (long from = first; from <= last; from += 500) {
			int size = (int)Math.min(500, (long)last - from + 1);
			List<Object> arguments = new ArrayList<>(size * 2);
			for (int offset = 0; offset < size; offset++) {
				arguments.add(couponId);
				arguments.add(from + offset);
			}
			String values = String.join(",", java.util.Collections.nCopies(size, "(?, ?)"));
			int changed = jdbcTemplate.update("insert into " + strategy.unitTable()
				+ " (coupon_id, unit_no) values " + values, arguments.toArray());
			if (changed != size) throw new IllegalStateException("쿠폰 재고 준비 건수가 일치하지 않습니다.");
		}
	}

	public int removeAbove(long couponId, CouponIssueStrategy strategy, int target) {
		return entityManager.createNativeQuery("delete from " + strategy.unitTable()
			+ " where coupon_id = :id and unit_no > :target")
			.setParameter("id", couponId).setParameter("target", target).executeUpdate();
	}
}
