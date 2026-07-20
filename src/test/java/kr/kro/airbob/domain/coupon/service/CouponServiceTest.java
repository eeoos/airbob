package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.dto.CouponRequest;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyPreparedException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

	private static final LocalDateTime ISSUE_START = LocalDateTime.of(2026, 7, 18, 10, 0);
	private static final LocalDateTime ISSUE_END = ISSUE_START.plusMinutes(10);

	@Mock
	private CouponRepository couponRepository;
	@Mock
	private CouponRedisStockManager stockManager;

	private CouponService service;
	private Coupon coupon;

	@BeforeEach
	void setUp() {
		service = new CouponService(couponRepository, stockManager);
		coupon = Coupon.builder()
			.id(1L)
			.name("기존 이름")
			.discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(10_000)
			.issueStartAt(ISSUE_START)
			.issueEndAt(ISSUE_END)
			.usableFrom(ISSUE_START)
			.usableUntil(ISSUE_START.plusDays(30))
			.isActive(true)
			.totalQuantity(100)
			.issuedQuantity(0)
			.build();
		when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
	}

	@Test
	@DisplayName("준비된 쿠폰의 발급 설정은 변경할 수 없다")
	void rejectsIssuanceConfigurationChangeAfterPreparation() {
		when(stockManager.isPrepared(1L)).thenReturn(true);
		CouponRequest.Update update = update(null, ISSUE_START.plusMinutes(1), null, null, null);

		assertThatThrownBy(() -> service.updateCoupon(update, 1L))
			.isInstanceOf(CouponAlreadyPreparedException.class);
		assertThat(coupon.getIssueStartAt()).isEqualTo(ISSUE_START);
	}

	@Test
	@DisplayName("준비된 쿠폰도 표시 정보와 사용 기간은 변경할 수 있다")
	void allowsDisplayAndUsageChangesAfterPreparation() {
		when(stockManager.isPrepared(1L)).thenReturn(true);
		LocalDateTime changedUsableUntil = ISSUE_START.plusDays(60);
		CouponRequest.Update update = update("새 이름", null, changedUsableUntil, null, null);

		service.updateCoupon(update, 1L);

		assertThat(coupon.getName()).isEqualTo("새 이름");
		assertThat(coupon.getUsableUntil()).isEqualTo(changedUsableUntil);
	}

	@Test
	@DisplayName("준비된 쿠폰은 비활성화할 수 없다")
	void rejectsDeactivationAfterPreparation() {
		when(stockManager.isPrepared(1L)).thenReturn(true);

		assertThatThrownBy(() -> service.deleteCoupon(1L))
			.isInstanceOf(CouponAlreadyPreparedException.class);
		assertThat(coupon.getIsActive()).isTrue();
	}

	@Test
	@DisplayName("Redis 키가 만료돼도 DB 준비 이력으로 발급 설정 변경을 막는다")
	void rejectsIssuanceChangeAfterRedisKeyExpiration() {
		coupon.markRedisStockPrepared(ISSUE_START.minusHours(1));
		CouponRequest.Update update = update(null, ISSUE_START.plusMinutes(1), null, null, null);

		assertThatThrownBy(() -> service.updateCoupon(update, 1L))
			.isInstanceOf(CouponAlreadyPreparedException.class);
	}

	@Test
	@DisplayName("Redis 키가 만료돼도 DB 준비 이력으로 비활성화를 막는다")
	void rejectsDeactivationAfterRedisKeyExpiration() {
		coupon.markRedisStockPrepared(ISSUE_START.minusHours(1));

		assertThatThrownBy(() -> service.deleteCoupon(1L))
			.isInstanceOf(CouponAlreadyPreparedException.class);
	}

	@Test
	@DisplayName("준비되지 않은 쿠폰은 비활성화한다")
	void deactivatesUnpreparedCoupon() {
		when(stockManager.isPrepared(1L)).thenReturn(false);

		service.deleteCoupon(1L);

		assertThat(coupon.getIsActive()).isFalse();
		verify(couponRepository).findByIdForUpdate(1L);
	}

	private CouponRequest.Update update(
		String name,
		LocalDateTime issueStartAt,
		LocalDateTime usableUntil,
		Boolean active,
		Integer totalQuantity
	) {
		return new CouponRequest.Update(
			name,
			null,
			null,
			null,
			null,
			null,
			issueStartAt,
			null,
			null,
			usableUntil,
			active,
			totalQuantity);
	}
}
