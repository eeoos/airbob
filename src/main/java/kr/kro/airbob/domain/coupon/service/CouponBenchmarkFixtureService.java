package kr.kro.airbob.domain.coupon.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.dto.CouponBenchmarkFixture;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyPreparedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotFoundException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;

@Service
@Profile("coupon-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class CouponBenchmarkFixtureService {

	private final CouponRepository couponRepository;
	private final CouponRedisStockManager stockManager;
	private final CouponTimeProvider timeProvider;
	private final JdbcTemplate jdbcTemplate;

	@Transactional
	public CouponBenchmarkFixture.Created create(CouponBenchmarkFixture.Create request) {
		LocalDateTime now = timeProvider.now();
		Coupon coupon = couponRepository.save(Coupon.builder()
			.name("coupon-benchmark-" + request.label() + "-" + UUID.randomUUID())
			.description(owner(request.runId(), request.variant()))
			.discountType(DiscountType.FIXED_AMOUNT).discountValue(10_000)
			.issueStartAt(now.minusMinutes(1)).issueEndAt(now.plusSeconds(request.lifetimeSeconds()))
			.usableFrom(now.minusMinutes(1)).usableUntil(now.plusDays(1))
			.isActive(true).totalQuantity(request.stock()).issuedQuantity(0).build());
		return new CouponBenchmarkFixture.Created(coupon.getId(), request.runId(), request.variant(),
			coupon.getTotalQuantity(), coupon.getIssueEndAt());
	}

	@Transactional(readOnly = true)
	public CouponBenchmarkFixture.State state(Long couponId, String runId) {
		Coupon coupon = couponRepository.findById(couponId).orElseThrow(CouponNotFoundException::new);
		String variant = ownedVariant(coupon, runId);
		long[] counts = jdbcTemplate.queryForObject("""
			select count(*), count(distinct member_id) from member_coupon where coupon_id = ?
			""", (row, number) -> new long[] {row.getLong(1), row.getLong(2)}, couponId);
		boolean prepared = coupon.isRedisStockPrepared();
		return new CouponBenchmarkFixture.State(couponId, runId, variant, coupon.getTotalQuantity(),
			coupon.getIssuedQuantity(), counts[0], counts[1], prepared,
			prepared ? stockManager.remainingStock(couponId) : null,
			prepared ? stockManager.issuedMemberCount(couponId) : null, coupon.getIsActive());
	}

	/** DB 비교 쿠폰만 비활성화한다. 준비된 Lua 쿠폰은 기존 만료 정책을 따른다. */
	@Transactional
	public void close(Long couponId, String runId) {
		Coupon coupon = couponRepository.findByIdForUpdate(couponId).orElseThrow(CouponNotFoundException::new);
		if (!"db".equals(ownedVariant(coupon, runId)) || coupon.isRedisStockPrepared()) {
			throw new CouponAlreadyPreparedException();
		}
		coupon.deactivate();
	}

	private String ownedVariant(Coupon coupon, String runId) {
		for (String variant : new String[] {"db", "lua"}) {
			if (coupon.getName().startsWith("coupon-benchmark-")
				&& owner(runId, variant).equals(coupon.getDescription())) {
				return variant;
			}
		}
		throw new CouponNotFoundException();
	}

	private String owner(String runId, String variant) {
		return "coupon-benchmark:" + runId + ":" + variant;
	}
}
