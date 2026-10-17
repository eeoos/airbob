package kr.kro.airbob.domain.coupon.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import kr.kro.airbob.domain.coupon.common.*;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.exception.*;
import kr.kro.airbob.domain.coupon.repository.*;
class CouponStockPreparationServiceTest {
	@Test void rejectsInactiveAndPreviouslyIssuedCampaigns() {
		var coupons = mock(CouponRepository.class);
		var members = mock(MemberCouponRepository.class);
		var units = mock(CouponIssueUnitStore.class);
		var service = new CouponStockPreparationService(coupons, members, units);
		var now = LocalDateTime.of(2026, 10, 9, 10, 0);
		when(units.databaseNow()).thenReturn(now);
		var coupon = Coupon.builder().id(1L).name("coupon").totalQuantity(10)
			.isActive(false).issueStartAt(now.plusHours(1)).issueEndAt(now.plusHours(2)).build();
		when(coupons.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
		assertThatThrownBy(() -> service.prepare(1L)).isInstanceOf(CouponStockPreparationNotAllowedException.class);
		coupon.activate();
		when(members.countByCouponId(1L)).thenReturn(1L);
		assertThatThrownBy(() -> service.prepare(1L)).isInstanceOf(CouponStockPreparationNotAllowedException.class);
		verify(units, never()).insertRange(anyLong(), anyInt(), anyInt());
	}
}
