package kr.kro.airbob.core.domain.coupon;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CouponTimeProvider {

	private final Clock clock;

	public LocalDateTime now() {
		return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}
}
