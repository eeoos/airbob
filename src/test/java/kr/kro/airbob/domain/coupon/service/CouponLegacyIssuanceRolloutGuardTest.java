package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import kr.kro.airbob.domain.coupon.repository.CouponRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("쿠폰 Lua 전환 배포 가드 테스트")
class CouponLegacyIssuanceRolloutGuardTest {

	@Mock
	private CouponRepository couponRepository;

	private CouponLegacyIssuanceRolloutGuard rolloutGuard;

	@BeforeEach
	void setUp() {
		rolloutGuard = new CouponLegacyIssuanceRolloutGuard(couponRepository);
	}

	@Test
	@DisplayName("활성·미준비 쿠폰의 기존 발급이 없으면 시작을 허용한다")
	void allowsStartupWhenNoLegacyIssuanceExists() {
		when(couponRepository.countActiveUnpreparedCouponsWithLegacyIssuances()).thenReturn(0L);

		assertThatCode(() -> rolloutGuard.run(new DefaultApplicationArguments()))
			.doesNotThrowAnyException();

		verify(couponRepository).countActiveUnpreparedCouponsWithLegacyIssuances();
	}

	@Test
	@DisplayName("활성·미준비 쿠폰에 기존 발급이 있으면 해결 전까지 배포를 차단한다")
	void blocksStartupWhenLegacyIssuanceExists() {
		when(couponRepository.countActiveUnpreparedCouponsWithLegacyIssuances()).thenReturn(2L);

		assertThatThrownBy(() -> rolloutGuard.run(new DefaultApplicationArguments()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("배포를 차단")
			.hasMessageContaining("2개")
			.hasMessageContaining("마이그레이션하거나 운영 절차로 해결")
			.hasMessageContaining("쿠폰 데이터를 변경하지 않습니다");
	}
}
