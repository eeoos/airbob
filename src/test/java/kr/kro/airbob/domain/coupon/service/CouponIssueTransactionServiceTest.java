package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyIssuedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotIssuableException;
import kr.kro.airbob.domain.coupon.exception.CouponSoldOutException;
import kr.kro.airbob.domain.coupon.exception.CouponStockNotPreparedException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class CouponIssueTransactionServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 18, 10, 1);

	@Mock
	private CouponRepository couponRepository;
	@Mock
	private MemberCouponRepository memberCouponRepository;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private CouponTimeProvider timeProvider;

	private CouponIssueTransactionService service;

	@BeforeEach
	void setUp() {
		service = new CouponIssueTransactionService(
			couponRepository, memberCouponRepository, memberRepository, timeProvider);
	}

	@Test
	@DisplayName("DB 경로는 발급 가능 상태, 회원 중복, 재고 순서로 검증한다")
	void duplicateTakesPrecedenceOverSoldOut() {
		Coupon soldOut = coupon(true, 10, 10);
		when(couponRepository.findById(1L)).thenReturn(Optional.of(soldOut));
		when(timeProvider.now()).thenReturn(NOW);
		when(memberCouponRepository.existsByMemberIdAndCouponId(10L, 1L)).thenReturn(true);

		assertThatThrownBy(() -> service.issueWithConditionalUpdate(1L, 10L))
			.isInstanceOf(CouponAlreadyIssuedException.class);
		verify(couponRepository, never()).incrementIssuedQuantity(1L);
	}

	@Test
	@DisplayName("발급 불가 상태는 회원 중복이나 재고보다 먼저 거절한다")
	void notIssuableTakesPrecedenceOverDuplicate() {
		when(timeProvider.now()).thenReturn(NOW);
		Coupon inactive = coupon(false, 10, 10);
		when(couponRepository.findById(1L)).thenReturn(Optional.of(inactive));

		assertThatThrownBy(() -> service.issueWithConditionalUpdate(1L, 10L))
			.isInstanceOf(CouponNotIssuableException.class);
		verify(memberCouponRepository, never()).existsByMemberIdAndCouponId(10L, 1L);
	}

	@Test
	@DisplayName("조건부 UPDATE 성공 여부로 발급하고 증가 후 조회한 마지막 재고도 허용한다")
	void issuesWithConditionalUpdate() {
		Coupon coupon = coupon(true, 10, 10);
		Member member = org.mockito.Mockito.mock(Member.class);
		when(timeProvider.now()).thenReturn(NOW);
		when(couponRepository.incrementIssuedQuantityIfIssuable(1L, NOW)).thenReturn(1);
		when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
		when(memberRepository.getReferenceById(10L)).thenReturn(member);

		service.issueWithConditionalUpdate(1L, 10L);

		var order = inOrder(couponRepository, memberCouponRepository);
		order.verify(couponRepository).incrementIssuedQuantityIfIssuable(1L, NOW);
		order.verify(couponRepository).findById(1L);
		order.verify(memberCouponRepository).existsByMemberIdAndCouponId(10L, 1L);
		order.verify(memberCouponRepository).save(any(MemberCoupon.class));
		verify(couponRepository, never()).findByIdForUpdate(1L);
		verify(couponRepository, never()).incrementIssuedQuantity(1L);
	}

	@Test
	@DisplayName("Redis 재고를 준비한 쿠폰은 DB 경로로 발급하지 않는다")
	void rejectsRedisPreparedCampaignOnDbPath() {
		when(timeProvider.now()).thenReturn(NOW);
		Coupon coupon = coupon(true, 10, 0);
		coupon.markRedisStockPrepared(NOW.minusHours(1));
		when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));

		assertThatThrownBy(() -> service.issueWithConditionalUpdate(1L, 10L))
			.isInstanceOf(CouponNotIssuableException.class);
		verify(couponRepository, never()).incrementIssuedQuantity(1L);
	}

	@Test
	@DisplayName("조건부 UPDATE가 0행이면 발급 기록을 만들지 않고 매진으로 응답한다")
	void rejectsWhenConditionalUpdateDoesNotReserveStock() {
		when(timeProvider.now()).thenReturn(NOW);
		when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon(true, 10, 10)));

		assertThatThrownBy(() -> service.issueWithConditionalUpdate(1L, 10L))
			.isInstanceOf(CouponSoldOutException.class);
		verify(memberCouponRepository, never()).save(any(MemberCoupon.class));
	}

	@Test
	@DisplayName("Lua 승인 후에는 Redis가 검증한 재고를 다시 차감하지 않고 DB 결과만 저장한다")
	void persistsApprovedIssue() {
		Coupon coupon = coupon(true, 10, 10);
		coupon.markRedisStockPrepared(NOW.minusHours(1));
		Member member = org.mockito.Mockito.mock(Member.class);
		when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
		when(memberRepository.getReferenceById(10L)).thenReturn(member);

		service.persistApprovedIssue(1L, 10L);

		verify(couponRepository).incrementIssuedQuantity(1L);
		verify(memberCouponRepository).save(any(MemberCoupon.class));
	}

	@Test
	@DisplayName("DB 준비 이력이 없는 쿠폰은 Lua 승인 결과도 저장하지 않는다")
	void rejectsLuaPersistenceWithoutPreparationMarker() {
		Coupon coupon = coupon(true, 10, 0);
		when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));

		assertThatThrownBy(() -> service.persistApprovedIssue(1L, 10L))
			.isInstanceOf(CouponStockNotPreparedException.class);
		verify(couponRepository, never()).incrementIssuedQuantity(1L);
	}

	private Coupon coupon(boolean active, int totalQuantity, int issuedQuantity) {
		return Coupon.builder()
			.id(1L)
			.name("선착순 쿠폰")
			.discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(10_000)
			.issueStartAt(NOW.minusMinutes(1))
			.issueEndAt(NOW.plusMinutes(1))
			.usableFrom(NOW)
			.usableUntil(NOW.plusDays(30))
			.isActive(active)
			.totalQuantity(totalQuantity)
			.issuedQuantity(issuedQuantity)
			.build();
	}
}
