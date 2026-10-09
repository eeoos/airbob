package kr.kro.airbob.domain.coupon.dto;

import java.time.LocalDateTime;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class CouponBenchmarkFixture {
	private CouponBenchmarkFixture() { }

	public record Create(
		@NotNull @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String runId,
		@NotNull @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String label,
		@NotNull @Pattern(regexp = "db|skip_surrogate_rr|skip_surrogate_rc|skip_composite_rr|skip_composite_rc") String variant,
		@NotNull @Min(1) @Max(20_000_000) Integer stock,
		@NotNull @Min(60) @Max(3600) Integer lifetimeSeconds
	) { }

	public record Created(Long couponId, String runId, String variant, Integer stock,
		LocalDateTime issueStartAt, LocalDateTime issueEndAt) { }

	public record State(Long couponId, String runId, String variant, Integer stock,
		long issuedQuantity, long memberCouponCount, long distinctMemberCount,
		Integer legacyIssuedQuantity, boolean stockPrepared, Long remainingUnitCount, boolean active) { }
}
