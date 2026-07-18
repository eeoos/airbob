package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
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
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyPreparedException;
import kr.kro.airbob.domain.coupon.exception.CouponStockPreparationNotAllowedException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;

@ExtendWith(MockitoExtension.class)
class CouponStockPreparationServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 18, 9, 0);
	private static final LocalDateTime ISSUE_START = LocalDateTime.of(2026, 7, 18, 10, 0);
	private static final LocalDateTime ISSUE_END = LocalDateTime.of(2026, 7, 18, 10, 10);

	@Mock
	private CouponRepository couponRepository;
	@Mock
	private MemberCouponRepository memberCouponRepository;
	@Mock
	private CouponRedisStockManager stockManager;
	@Mock
	private CouponTimeProvider timeProvider;

	private CouponStockPreparationService service;

	@BeforeEach
	void setUp() {
		service = new CouponStockPreparationService(
			couponRepository, memberCouponRepository, stockManager, timeProvider);
		when(timeProvider.now()).thenReturn(NOW);
	}

	@Test
	@DisplayName("미발급 활성 유한 쿠폰을 시작 전에 한 번 준비한다")
	void preparesUntouchedFiniteCoupon() {
		Coupon coupon = coupon(true, 100, 0, ISSUE_START);
		when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
		when(memberCouponRepository.countByCouponId(1L)).thenReturn(0L);
		when(timeProvider.toEpochMilli(ISSUE_START)).thenReturn(1_000L);
		when(timeProvider.toEpochMilli(ISSUE_END)).thenReturn(2_000L);
		when(timeProvider.toEpochMilli(ISSUE_END.plusDays(7))).thenReturn(3_000L);
		when(stockManager.prepare(1L, 100, 1_000L, 2_000L, true, 3_000L))
			.thenReturn(CouponRedisPreparationResult.PREPARED);

		service.prepare(1L);

		verify(stockManager).prepare(1L, 100, 1_000L, 2_000L, true, 3_000L);
	}

	@Test
	@DisplayName("무제한 쿠폰은 Redis 재고를 준비할 수 없다")
	void rejectsUnlimitedCoupon() {
		assertPreparationRejected(coupon(true, null, 0, ISSUE_START), 0L);
	}

	@Test
	@DisplayName("비활성 쿠폰은 Redis 재고를 준비할 수 없다")
	void rejectsInactiveCoupon() {
		assertPreparationRejected(coupon(false, 100, 0, ISSUE_START), 0L);
	}

	@Test
	@DisplayName("발급이 시작된 쿠폰은 Redis 재고를 준비할 수 없다")
	void rejectsStartedCoupon() {
		assertPreparationRejected(coupon(true, 100, 0, NOW), 0L);
	}

	@Test
	@DisplayName("DB 발급 수가 존재하면 Redis 재고를 준비할 수 없다")
	void rejectsCouponWithIssuedQuantity() {
		assertPreparationRejected(coupon(true, 100, 1, ISSUE_START), 0L);
	}

	@Test
	@DisplayName("실제 회원 쿠폰이 존재하면 Redis 재고를 준비할 수 없다")
	void rejectsCouponWithMemberCoupon() {
		assertPreparationRejected(coupon(true, 100, 0, ISSUE_START), 1L);
	}

	@Test
	@DisplayName("이미 Redis에 준비된 쿠폰은 덮어쓰지 않는다")
	void rejectsAlreadyPreparedCoupon() {
		Coupon coupon = coupon(true, 100, 0, ISSUE_START);
		when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
		when(memberCouponRepository.countByCouponId(1L)).thenReturn(0L);
		when(timeProvider.toEpochMilli(ISSUE_START)).thenReturn(1_000L);
		when(timeProvider.toEpochMilli(ISSUE_END)).thenReturn(2_000L);
		when(timeProvider.toEpochMilli(ISSUE_END.plusDays(7))).thenReturn(3_000L);
		when(stockManager.prepare(1L, 100, 1_000L, 2_000L, true, 3_000L))
			.thenReturn(CouponRedisPreparationResult.ALREADY_PREPARED);

		assertThatThrownBy(() -> service.prepare(1L))
			.isInstanceOf(CouponAlreadyPreparedException.class);
	}

	private void assertPreparationRejected(Coupon coupon, long memberCouponCount) {
		when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
		when(memberCouponRepository.countByCouponId(1L)).thenReturn(memberCouponCount);

		assertThatThrownBy(() -> service.prepare(1L))
			.isInstanceOf(CouponStockPreparationNotAllowedException.class);
		verify(stockManager, never()).prepare(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyBoolean(),
			org.mockito.ArgumentMatchers.anyLong());
	}

	private Coupon coupon(boolean active, Integer totalQuantity, int issuedQuantity, LocalDateTime issueStartAt) {
		return Coupon.builder()
			.id(1L)
			.name("오전 10시 선착순 쿠폰")
			.discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(10_000)
			.issueStartAt(issueStartAt)
			.issueEndAt(ISSUE_END)
			.usableFrom(ISSUE_START)
			.usableUntil(ISSUE_START.plusDays(30))
			.isActive(active)
			.totalQuantity(totalQuantity)
			.issuedQuantity(issuedQuantity)
			.build();
	}
}
