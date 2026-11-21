package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("쿠폰 사용 서비스 테스트")
class CouponUsageServiceTest {

	private static final long MEMBER_ID = 7L;
	private static final long COUPON_ID = 11L;
	private static final long MEMBER_COUPON_ID = 13L;
	private static final long RESERVATION_ID = 17L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 15, 0);

	@Mock private MemberCouponRepository memberCouponRepository;
	@Mock private CouponRepository couponRepository;
	@Mock private CouponTimeProvider timeProvider;
	@Mock private MemberCoupon memberCoupon;
	@Mock private Coupon coupon;

	@InjectMocks private CouponUsageService couponUsageService;

	@Test
	@DisplayName("checkout은 쿠폰 정책 공유 락을 잡은 뒤 할인 계산과 조건부 사용을 수행한다")
	void locksCouponPolicyBeforeConsumingMemberCoupon() {
		given(memberCouponRepository.findByMemberIdAndCouponId(MEMBER_ID, COUPON_ID))
			.willReturn(Optional.of(memberCoupon));
		given(couponRepository.findByIdForShare(COUPON_ID)).willReturn(Optional.of(coupon));
		given(timeProvider.now()).willReturn(NOW);
		given(coupon.isUsable(NOW)).willReturn(true);
		given(coupon.calculateDiscount(100_000L)).willReturn(10_000L);
		given(memberCoupon.getId()).willReturn(MEMBER_COUPON_ID);
		given(memberCouponRepository.markUsed(MEMBER_COUPON_ID, RESERVATION_ID, NOW))
			.willReturn(1);

		long discount = couponUsageService.use(
			MEMBER_ID, COUPON_ID, RESERVATION_ID, 100_000L);

		assertThat(discount).isEqualTo(10_000L);
		InOrder order = Mockito.inOrder(memberCouponRepository, couponRepository, coupon);
		order.verify(memberCouponRepository).findByMemberIdAndCouponId(MEMBER_ID, COUPON_ID);
		order.verify(couponRepository).findByIdForShare(COUPON_ID);
		order.verify(coupon).isUsable(NOW);
		order.verify(coupon).calculateDiscount(100_000L);
		order.verify(memberCouponRepository)
			.markUsed(MEMBER_COUPON_ID, RESERVATION_ID, NOW);
	}

	@Test
	@DisplayName("quote 미리보기는 쿠폰 정책을 잠그거나 사용 처리하지 않는다")
	void previewDoesNotLockOrConsumeCoupon() {
		given(memberCouponRepository.findByMemberIdAndCouponIdWithCoupon(MEMBER_ID, COUPON_ID))
			.willReturn(Optional.of(memberCoupon));
		given(memberCoupon.isUsed()).willReturn(false);
		given(memberCoupon.getCoupon()).willReturn(coupon);
		given(timeProvider.now()).willReturn(NOW);
		given(coupon.isUsable(NOW)).willReturn(true);
		given(coupon.calculateDiscount(100_000L)).willReturn(10_000L);

		assertThat(couponUsageService.preview(MEMBER_ID, COUPON_ID, 100_000L))
			.isEqualTo(10_000L);

		then(couponRepository).shouldHaveNoInteractions();
		then(memberCouponRepository).shouldHaveNoMoreInteractions();
	}
}
