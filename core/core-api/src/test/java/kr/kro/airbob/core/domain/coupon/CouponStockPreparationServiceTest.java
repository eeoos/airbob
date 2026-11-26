package kr.kro.airbob.core.domain.coupon;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import kr.kro.airbob.core.domain.coupon.repository.CouponIssueUnitStore;
import kr.kro.airbob.core.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.core.domain.coupon.repository.MemberCouponRepository;

class CouponStockPreparationServiceTest {
	@Test
	void rejectsInactiveAndPreviouslyIssuedCampaigns() {
		var coupons = mock(CouponRepository.class);
		var members = mock(MemberCouponRepository.class);
		var units = mock(CouponIssueUnitStore.class);
		var service = new CouponStockPreparationService(new CouponReader(coupons, members, units),
			new CouponWriter(coupons, units));
		var now = LocalDateTime.of(2026, 10, 9, 10, 0);
		given(units.databaseNow()).willReturn(now);
		var coupon = Coupon.builder().id(1L).name("coupon").totalQuantity(10)
			.isActive(false).issueStartAt(now.plusHours(1)).issueEndAt(now.plusHours(2)).build();
		given(coupons.findByIdForUpdate(1L)).willReturn(Optional.of(coupon));
		assertThatThrownBy(() -> service.prepare(1L)).isInstanceOf(CouponStockPreparationNotAllowedException.class);
		coupon.activate();
		given(members.countByCouponId(1L)).willReturn(1L);
		assertThatThrownBy(() -> service.prepare(1L)).isInstanceOf(CouponStockPreparationNotAllowedException.class);
		then(units).should(never()).insertRange(anyLong(), anyInt(), anyInt());
	}
}
