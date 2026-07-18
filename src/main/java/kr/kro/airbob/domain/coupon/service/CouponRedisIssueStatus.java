package kr.kro.airbob.domain.coupon.service;

public enum CouponRedisIssueStatus {
	APPROVED,
	SOLD_OUT,
	DUPLICATE,
	NOT_STARTED,
	ENDED,
	UNPREPARED,
	INACTIVE
}
