package kr.kro.airbob.domain.coupon.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class CouponBenchmarkFixture {

	private CouponBenchmarkFixture() {
	}

	public record Create(
		@NotNull @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String runId,
		@NotNull @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String label,
		@NotNull @Pattern(regexp = "db|lua") String variant,
		@NotNull @Min(1) @Max(20_000_000) Integer stock,
		@NotNull @Min(60) @Max(3600) Integer lifetimeSeconds
	) {
	}

	public record Created(Long couponId, String runId, String variant, Integer stock,
		LocalDateTime issueEndAt) {
	}

	public record State(Long couponId, String runId, String variant, Integer stock,
		Integer issuedQuantity, long memberCouponCount, long distinctMemberCount,
		boolean redisPrepared, Long redisRemainingStock, Long redisIssuedCount, boolean active) {
	}
}
