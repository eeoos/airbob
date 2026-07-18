package kr.kro.airbob.domain.coupon.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

@Component
public class CouponTimeProvider {

	private static final ZoneId COUPON_ZONE = ZoneId.of("Asia/Seoul");

	public LocalDateTime now() {
		return LocalDateTime.now(COUPON_ZONE);
	}

	public long toEpochMilli(LocalDateTime dateTime) {
		return dateTime.atZone(COUPON_ZONE).toInstant().toEpochMilli();
	}
}
