package kr.kro.airbob.domain.coupon.repository.querydsl;

import static kr.kro.airbob.domain.coupon.entity.QCoupon.coupon;
import static kr.kro.airbob.domain.coupon.entity.QMemberCoupon.memberCoupon;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.domain.coupon.repository.projection.MemberCouponProjection;
import kr.kro.airbob.domain.coupon.repository.projection.QMemberCouponProjection;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MemberCouponRepositoryImpl implements MemberCouponRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	@Override
	public Slice<MemberCouponProjection> findByMemberIdWithCursor(
		Long memberId,
		Long lastId,
		LocalDateTime lastCreatedAt,
		Pageable pageable
	) {
		List<MemberCouponProjection> content = queryFactory
			.select(new QMemberCouponProjection(
				memberCoupon.id, memberCoupon.createdAt,
				coupon.id, coupon.name, coupon.description, coupon.discountType, coupon.discountValue,
				coupon.minPaymentPrice, coupon.maxDiscountAmount, coupon.usableFrom, coupon.usableUntil,
				memberCoupon.used, coupon.isActive))
			.from(memberCoupon)
			.join(memberCoupon.coupon, coupon)
			.where(memberCoupon.member.id.eq(memberId), cursorCondition(lastId, lastCreatedAt))
			.orderBy(memberCoupon.createdAt.desc(), memberCoupon.id.desc())
			.limit((long)pageable.getPageSize() + 1)
			.fetch();

		boolean hasNext = content.size() > pageable.getPageSize();
		if (hasNext) {
			content.remove(pageable.getPageSize());
		}
		return new SliceImpl<>(content, pageable, hasNext);
	}

	private BooleanExpression cursorCondition(Long lastId, LocalDateTime lastCreatedAt) {
		if (lastId == null || lastCreatedAt == null) {
			return null;
		}
		return memberCoupon.createdAt.lt(lastCreatedAt)
			.or(memberCoupon.createdAt.eq(lastCreatedAt).and(memberCoupon.id.lt(lastId)));
	}
}
