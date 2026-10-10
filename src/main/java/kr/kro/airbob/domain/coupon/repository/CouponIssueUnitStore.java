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

/** Selects the fixed-table Spring Data repositories while the key strategies are compared. */
@Repository
@RequiredArgsConstructor
public class CouponIssueUnitStore {

	private final CouponRepository coupons;
	private final CouponIssueUnitRepository surrogateUnits;
	private final CouponIssueUnitCompositeRepository compositeUnits;
	private final EntityManager entityManager;
	private final JdbcTemplate jdbcTemplate;

	public record Unit(long key, int number) { }

	public void clearPersistenceContext() {
		// OSIV may retain an old Coupon even across transactions. This is the only direct JPA operation.
		entityManager.clear();
	}

	public LocalDateTime databaseNow() {
		// Coupon DATETIME values are UTC. This query is independent of the DB session time zone.
		return coupons.findDatabaseUtcNow();
	}

	public CouponIssueStrategy strategy(long couponId) {
		return CouponIssueStrategy.valueOf(coupons.findIssueStrategy(couponId).orElseThrow(CouponNotFoundException::new));
	}

	public Optional<Unit> lockAvailable(long couponId, CouponIssueStrategy strategy) {
		return (composite(strategy) ? compositeUnits.lockAvailable(couponId) : surrogateUnits.lockAvailable(couponId))
			.map(row -> new Unit(row.getUnitKey(), row.getUnitNumber()));
	}

	public void consume(long couponId, CouponIssueStrategy strategy, Unit unit) {
		int changed = composite(strategy)
			? compositeUnits.consume(couponId, unit.key()) : surrogateUnits.consume(couponId, unit.key());
		if (changed != 1) throw new IllegalStateException("확보한 쿠폰 재고를 소비하지 못했습니다.");
	}

	public boolean hasRemaining(long couponId, CouponIssueStrategy strategy) {
		return (composite(strategy) ? compositeUnits.findRemainingUnit(couponId) : surrogateUnits.findRemainingUnit(couponId))
			.isPresent();
	}

	public long count(long couponId, CouponIssueStrategy strategy) {
		return composite(strategy) ? compositeUnits.countUnits(couponId) : surrogateUnits.countUnits(couponId);
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
		return composite(strategy) ? compositeUnits.removeAbove(couponId, target) : surrogateUnits.removeAbove(couponId, target);
	}

	private boolean composite(CouponIssueStrategy strategy) {
		if (strategy == CouponIssueStrategy.DB) throw new IllegalArgumentException("조건부 UPDATE는 unit을 사용하지 않습니다.");
		return strategy.composite();
	}
}
