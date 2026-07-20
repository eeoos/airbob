package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
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
		assertThat(coupon.getRedisStockPreparedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("미발급 활성 무제한 쿠폰을 Redis에 준비한다")
	void preparesUntouchedUnlimitedCoupon() {
		Coupon coupon = coupon(true, null, 0, ISSUE_START);
		stubPreparationDependencies(coupon);
		when(stockManager.prepare(1L, null, 1_000L, 2_000L, true, 3_000L))
			.thenReturn(CouponRedisPreparationResult.PREPARED);

		service.prepare(1L);

		verify(stockManager).prepare(1L, null, 1_000L, 2_000L, true, 3_000L);
		assertThat(coupon.getRedisStockPreparedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("비활성 쿠폰은 Redis 재고를 준비할 수 없다")
	void rejectsInactiveCoupon() {
		assertPreparationRejected(coupon(false, 100, 0, NOW.minusMinutes(1)), 0L);
	}

	@Test
	@DisplayName("발급이 시작됐어도 발급 이력이 없고 종료 전이면 Redis 재고를 준비한다")
	void preparesStartedCouponWithoutIssuances() {
		Coupon coupon = coupon(true, 100, 0, NOW.minusMinutes(1));
		stubPreparationDependencies(coupon);
		when(stockManager.prepare(1L, 100, 1_000L, 2_000L, true, 3_000L))
			.thenReturn(CouponRedisPreparationResult.PREPARED);

		service.prepare(1L);

		verify(stockManager).prepare(1L, 100, 1_000L, 2_000L, true, 3_000L);
		assertThat(coupon.getRedisStockPreparedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("발급 기간이 종료된 쿠폰은 Redis 재고를 준비할 수 없다")
	void rejectsEndedCoupon() {
		assertPreparationRejected(
			coupon(true, 100, 0, NOW.minusMinutes(2), NOW),
			0L);
	}

	@Test
	@DisplayName("양수가 아닌 유한 재고는 Redis에 준비할 수 없다")
	void rejectsNonPositiveFiniteStock() {
		assertPreparationRejected(coupon(true, 0, 0, ISSUE_START), 0L);
		assertPreparationRejected(coupon(true, -1, 0, ISSUE_START), 0L);
	}

	@Test
	@DisplayName("DB 발급 수가 존재하면 Redis 재고를 준비할 수 없다")
	void rejectsCouponWithIssuedQuantity() {
		assertPreparationRejected(coupon(true, 100, 1, NOW.minusMinutes(1)), 0L);
	}

	@Test
	@DisplayName("실제 회원 쿠폰이 존재하면 Redis 재고를 준비할 수 없다")
	void rejectsCouponWithMemberCoupon() {
		assertPreparationRejected(coupon(true, 100, 0, NOW.minusMinutes(1)), 1L);
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

	@Test
	@DisplayName("Redis 키가 사라져도 DB에 준비 이력이 있으면 다시 준비하지 않는다")
	void rejectsPersistentlyPreparedCoupon() {
		Coupon coupon = coupon(true, 100, 0, ISSUE_START);
		coupon.markRedisStockPrepared(NOW.minusMinutes(1));

		assertPreparationRejected(coupon, 0L);
	}

	private void assertPreparationRejected(Coupon coupon, long memberCouponCount) {
		when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
		when(memberCouponRepository.countByCouponId(1L)).thenReturn(memberCouponCount);

		assertThatThrownBy(() -> service.prepare(1L))
			.isInstanceOf(CouponStockPreparationNotAllowedException.class);
		verify(stockManager, never()).prepare(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyBoolean(),
			org.mockito.ArgumentMatchers.anyLong());
	}

	private void stubPreparationDependencies(Coupon coupon) {
		when(couponRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(coupon));
		when(memberCouponRepository.countByCouponId(1L)).thenReturn(0L);
		when(timeProvider.toEpochMilli(coupon.getIssueStartAt())).thenReturn(1_000L);
		when(timeProvider.toEpochMilli(coupon.getIssueEndAt())).thenReturn(2_000L);
		when(timeProvider.toEpochMilli(coupon.getIssueEndAt().plusDays(7))).thenReturn(3_000L);
	}

	private Coupon coupon(boolean active, Integer totalQuantity, int issuedQuantity, LocalDateTime issueStartAt) {
		return coupon(active, totalQuantity, issuedQuantity, issueStartAt, ISSUE_END);
	}

	private Coupon coupon(
		boolean active,
		Integer totalQuantity,
		int issuedQuantity,
		LocalDateTime issueStartAt,
		LocalDateTime issueEndAt
	) {
		return Coupon.builder()
			.id(1L)
			.name("오전 10시 선착순 쿠폰")
			.discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(10_000)
			.issueStartAt(issueStartAt)
			.issueEndAt(issueEndAt)
			.usableFrom(ISSUE_START)
			.usableUntil(ISSUE_START.plusDays(30))
			.isActive(active)
			.totalQuantity(totalQuantity)
			.issuedQuantity(issuedQuantity)
			.build();
	}
}
