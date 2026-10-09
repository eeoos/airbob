package kr.kro.airbob.domain.coupon.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import kr.kro.airbob.domain.coupon.common.*;
import kr.kro.airbob.domain.coupon.dto.CouponRequest;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.exception.*;
import kr.kro.airbob.domain.coupon.repository.*;
class CouponServiceTest {
	@Test void doesNotMoveStartedCampaignBackIntoEditableState() {
		var coupons = mock(CouponRepository.class);
		var members = mock(MemberCouponRepository.class);
		var units = mock(CouponIssueUnitRepository.class);
		var service = new CouponService(coupons, members, units);
		var now = LocalDateTime.of(2026, 10, 9, 10, 0);
		var coupon = Coupon.builder().id(1L).issueStartAt(now.minusMinutes(1)).issueEndAt(now.plusHours(1))
			.totalQuantity(10).issuedQuantity(0).isActive(true).build();
		when(coupons.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
		when(units.databaseNow()).thenReturn(now);
		var request = new CouponRequest.Update(null,null,null,null,null,null,now.plusDays(1),null,null,null,null,20);
		assertThatThrownBy(() -> service.updateCoupon(request, 1L)).isInstanceOf(CouponConfigurationLockedException.class);
		assertThat(coupon.getTotalQuantity()).isEqualTo(10);
		verify(units, never()).insertRange(anyLong(), any(), anyInt(), anyInt());
	}
}
