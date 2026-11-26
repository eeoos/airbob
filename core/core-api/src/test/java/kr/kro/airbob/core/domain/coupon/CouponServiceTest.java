package kr.kro.airbob.core.domain.coupon;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import kr.kro.airbob.core.domain.coupon.repository.CouponIssueUnitStore;
import kr.kro.airbob.core.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.core.domain.coupon.repository.MemberCouponRepository;

class CouponServiceTest {
	@Test
	void doesNotMoveStartedCampaignBackIntoEditableState() {
		var coupons = mock(CouponRepository.class);
		var members = mock(MemberCouponRepository.class);
		var units = mock(CouponIssueUnitStore.class);
		var service = new CouponService(new CouponReader(coupons, members, units), new CouponWriter(coupons, units));
		var now = LocalDateTime.of(2026, 10, 9, 10, 0);
		var coupon = Coupon.builder().id(1L).issueStartAt(now.minusMinutes(1)).issueEndAt(now.plusHours(1))
			.totalQuantity(10).isActive(true).build();
		given(coupons.findByIdForUpdate(1L)).willReturn(Optional.of(coupon));
		given(units.databaseNow()).willReturn(now);
		var request = new CouponPatch(null, null, null, null, null, null, now.plusDays(1).toInstant(ZoneOffset.UTC),
			null, null, null, null, 20);
		assertThatThrownBy(() -> service.updateCoupon(request, 1L))
			.isInstanceOf(CouponConfigurationLockedException.class);
		assertThat(coupon.getTotalQuantity()).isEqualTo(10);
		then(units).should(never()).insertRange(anyLong(), anyInt(), anyInt());
	}
}
