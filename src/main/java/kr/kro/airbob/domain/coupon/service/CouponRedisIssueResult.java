package kr.kro.airbob.domain.coupon.service;

public record CouponRedisIssueResult(
	CouponRedisIssueStatus status,
	Long remainingStock
) {

	public CouponRedisIssueResult {
		if (status == CouponRedisIssueStatus.APPROVED && (remainingStock == null || remainingStock < 0)) {
			throw new IllegalArgumentException("승인 결과에는 0 이상의 잔여 재고가 필요합니다.");
		}
		if (status != CouponRedisIssueStatus.APPROVED && remainingStock != null) {
			throw new IllegalArgumentException("거절 결과에는 잔여 재고를 포함할 수 없습니다.");
		}
	}

	public static CouponRedisIssueResult approved(long remainingStock) {
		return new CouponRedisIssueResult(CouponRedisIssueStatus.APPROVED, remainingStock);
	}

	public static CouponRedisIssueResult rejected(CouponRedisIssueStatus status) {
		return new CouponRedisIssueResult(status, null);
	}

	public static CouponRedisIssueResult fromRawResult(long result) {
		if (result >= 0) {
			return approved(result);
		}

		return switch ((int)result) {
			case -1 -> rejected(CouponRedisIssueStatus.SOLD_OUT);
			case -2 -> rejected(CouponRedisIssueStatus.DUPLICATE);
			case -3 -> rejected(CouponRedisIssueStatus.NOT_STARTED);
			case -4 -> rejected(CouponRedisIssueStatus.ENDED);
			case -5 -> rejected(CouponRedisIssueStatus.UNPREPARED);
			case -6 -> rejected(CouponRedisIssueStatus.INACTIVE);
			default -> throw new IllegalArgumentException("알 수 없는 쿠폰 발급 Lua 결과: " + result);
		};
	}

	public boolean isApproved() {
		return status == CouponRedisIssueStatus.APPROVED;
	}
}
