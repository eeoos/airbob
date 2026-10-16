package kr.kro.airbob.domain.coupon.service;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.coupon.common.CouponIssueStrategy;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.dto.CouponBenchmarkFixture;
import kr.kro.airbob.domain.coupon.common.CouponTime;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.exception.CouponNotFoundException;
import kr.kro.airbob.domain.coupon.repository.CouponIssueUnitStore;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;

@Service
@Profile("coupon-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class CouponBenchmarkFixtureService {
	private final CouponRepository couponRepository;
	private final CouponStockPreparationService preparation;
	private final CouponIssueUnitStore units;
	private final JdbcTemplate jdbcTemplate;

	@Transactional
	public CouponBenchmarkFixture.Created create(CouponBenchmarkFixture.Create request) {
		var now = units.databaseNow();
		var strategy = CouponIssueStrategy.fromVariant(request.variant());
		var coupon = couponRepository.saveAndFlush(Coupon.builder()
			.name("coupon-benchmark-" + request.label() + "-" + UUID.randomUUID())
			.description(owner(request.runId(), request.variant()))
			.discountType(DiscountType.FIXED_AMOUNT).discountValue(10_000)
			.issueStartAt(now.plusHours(1)).issueEndAt(now.plusHours(2))
			.usableFrom(now.minusMinutes(1)).usableUntil(now.plusDays(1))
			.isActive(true).totalQuantity(request.stock()).issuedQuantity(0).issueStrategy(strategy).build());
		if (strategy != CouponIssueStrategy.DB) preparation.prepare(coupon.getId());
		// Preparation completes before the campaign is scheduled to open; no post-start refill.
		var start = units.databaseNow().plusSeconds(2);
		coupon.scheduleIssuance(start, start.plusSeconds(request.lifetimeSeconds()));
		return new CouponBenchmarkFixture.Created(coupon.getId(), request.runId(), strategy.variant(),
			coupon.getTotalQuantity(), CouponTime.toInstant(coupon.getIssueStartAt()),
			CouponTime.toInstant(coupon.getIssueEndAt()));
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public CouponBenchmarkFixture.State state(Long couponId, String runId) {
		var coupon = couponRepository.findById(couponId).orElseThrow(CouponNotFoundException::new);
		var strategy = ownedStrategy(coupon, runId);
		long[] counts = jdbcTemplate.queryForObject(
			"select count(*), count(distinct member_id) from member_coupon where coupon_id = ?",
			(row, number) -> new long[] {row.getLong(1), row.getLong(2)}, couponId);
		boolean baseline = strategy == CouponIssueStrategy.DB;
		return new CouponBenchmarkFixture.State(couponId, runId, strategy.variant(), coupon.getTotalQuantity(),
			counts[0], counts[0], counts[1], baseline ? coupon.getIssuedQuantity() : null,
			coupon.isStockPrepared(), baseline ? null : units.count(couponId, strategy), coupon.getIsActive());
	}

	/** Isolated fixture teardown only; never exposes post-start campaign edits on the public admin API. */
	@Transactional
	public void close(Long couponId, String runId) {
		var coupon = couponRepository.findByIdForUpdate(couponId).orElseThrow(CouponNotFoundException::new);
		ownedStrategy(coupon, runId);
		coupon.deactivate();
	}

	private CouponIssueStrategy ownedStrategy(Coupon coupon, String runId) {
		var strategy = coupon.getIssueStrategy();
		if (!coupon.getName().startsWith("coupon-benchmark-")
			|| !owner(runId, strategy.variant()).equals(coupon.getDescription())) throw new CouponNotFoundException();
		return strategy;
	}

	private String owner(String runId, String variant) { return "coupon-benchmark:" + runId + ":" + variant; }
}
