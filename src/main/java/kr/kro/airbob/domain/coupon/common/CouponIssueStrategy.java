package kr.kro.airbob.domain.coupon.common;

import java.util.Locale;

import org.springframework.transaction.TransactionDefinition;
import kr.kro.airbob.domain.coupon.exception.CouponInvalidConfigurationException;

public enum CouponIssueStrategy {
	DB,
	SKIP_SURROGATE_RR,
	SKIP_SURROGATE_RC,
	SKIP_COMPOSITE_RR,
	SKIP_COMPOSITE_RC;

	public String variant() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static CouponIssueStrategy fromVariant(String variant) {
		for (var strategy : values()) {
			if (strategy.variant().equals(variant)) return strategy;
		}
		throw new CouponInvalidConfigurationException();
	}

	public boolean composite() {
		return this == SKIP_COMPOSITE_RR || this == SKIP_COMPOSITE_RC;
	}

	public int isolation() {
		return this == SKIP_SURROGATE_RC || this == SKIP_COMPOSITE_RC
			? TransactionDefinition.ISOLATION_READ_COMMITTED : TransactionDefinition.ISOLATION_REPEATABLE_READ;
	}

	public String unitTable() {
		if (this == DB) throw new IllegalArgumentException("조건부 UPDATE는 unit을 사용하지 않습니다.");
		return composite() ? "coupon_issue_unit_composite" : "coupon_issue_unit";
	}
}
