package kr.kro.airbob.domain.coupon.repository.querydsl;

import static kr.kro.airbob.domain.coupon.entity.QCoupon.coupon;
import static kr.kro.airbob.domain.coupon.entity.QMemberCoupon.memberCoupon;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.domain.coupon.repository.projection.CouponCampaignProjection;
import kr.kro.airbob.domain.coupon.repository.projection.QCouponCampaignProjection;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CouponRepositoryImpl implements CouponRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	@Override
	public Slice<CouponCampaignProjection> findCampaignsWithCursor(
		LocalDateTime now,
		Long lastId,
		LocalDateTime lastIssueStartAt,
		Pageable pageable
	) {
		List<CouponCampaignProjection> content = queryFactory
			.select(new QCouponCampaignProjection(
				coupon.id, coupon.name, coupon.description, coupon.discountType, coupon.discountValue,
				coupon.minPaymentPrice, coupon.maxDiscountAmount, coupon.issueStartAt, coupon.issueEndAt,
				coupon.usableFrom, coupon.usableUntil, coupon.totalQuantity,
				JPAExpressions.select(memberCoupon.id.count())
					.from(memberCoupon)
					.where(memberCoupon.coupon.id.eq(coupon.id))))
			.from(coupon)
			.where(
				coupon.isActive.isTrue(),
				coupon.stockPreparedAt.isNotNull(),
				coupon.issueEndAt.gt(now),
				cursorCondition(lastId, lastIssueStartAt))
			.orderBy(coupon.issueStartAt.desc(), coupon.id.desc())
			.limit((long)pageable.getPageSize() + 1)
			.fetch();

		boolean hasNext = content.size() > pageable.getPageSize();
		if (hasNext) {
			content.remove(pageable.getPageSize());
		}
		return new SliceImpl<>(content, pageable, hasNext);
	}

	private BooleanExpression cursorCondition(Long lastId, LocalDateTime lastIssueStartAt) {
		if (lastId == null || lastIssueStartAt == null) {
			return null;
		}
		return coupon.issueStartAt.lt(lastIssueStartAt)
			.or(coupon.issueStartAt.eq(lastIssueStartAt).and(coupon.id.lt(lastId)));
	}
}
