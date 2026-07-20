package kr.kro.airbob.domain.coupon.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;

/**
 * Lua-only 운영 배포가 기존 Redisson 발급 쿠폰의 남은 재고를 고립시키지 않도록 시작을 차단한다.
 */
@Component
@Profile("(aws | oci) & !coupon-benchmark & !nplus1-benchmark & !read-model-benchmark")
@RequiredArgsConstructor
public class CouponLegacyIssuanceRolloutGuard implements ApplicationRunner {

	private final CouponRepository couponRepository;

	@Override
	public void run(ApplicationArguments args) {
		long blockedCouponCount =
			couponRepository.countActiveUnpreparedCouponsWithLegacyIssuances();
		if (blockedCouponCount == 0) {
			return;
		}

		throw new IllegalStateException("""
			쿠폰 Lua 전환 배포를 차단합니다: Redis 재고가 준비되지 않았지만 기존 Redisson 경로로 발급된 활성 쿠폰이 %d개 있습니다. 해당 쿠폰을 Redis Lua 재고로 마이그레이션하거나 운영 절차로 해결한 뒤 재배포하세요. 이 가드는 쿠폰 데이터를 변경하지 않습니다.
			""".formatted(blockedCouponCount).strip());
	}
}
