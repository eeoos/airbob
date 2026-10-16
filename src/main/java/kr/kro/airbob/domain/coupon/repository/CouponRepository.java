package kr.kro.airbob.domain.coupon.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.repository.projection.CouponCampaignProjection;
import kr.kro.airbob.domain.coupon.repository.projection.CouponCampaignAvailabilityProjection;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

	@Query(value = "select utc_timestamp(6)", nativeQuery = true)
	LocalDateTime findDatabaseUtcNow();

	@Query(value = "select issue_strategy from coupon where id = :id", nativeQuery = true)
	Optional<String> findIssueStrategy(@Param("id") Long id);

	@Query("""
		select new kr.kro.airbob.domain.coupon.repository.projection.CouponCampaignProjection(
			c.id, c.name, c.description, c.discountType, c.discountValue, c.minPaymentPrice,
			c.maxDiscountAmount, c.issueStartAt, c.issueEndAt, c.usableFrom, c.usableUntil,
			c.totalQuantity,
			(select count(mc.id) from MemberCoupon mc where mc.coupon = c))
		from Coupon c
		where c.isActive = true
		  and c.stockPreparedAt is not null
		  and c.issueEndAt > :now
		order by c.issueStartAt desc, c.id desc
		""")
	List<CouponCampaignProjection> findCampaigns(@Param("now") LocalDateTime now);

	/** Read-only candidate; V1 keeps findCampaigns until the experiment is evaluated. */
	@Query("""
		select new kr.kro.airbob.domain.coupon.repository.projection.CouponCampaignAvailabilityProjection(
			c.id, c.name, c.description, c.discountType, c.discountValue, c.minPaymentPrice,
			c.maxDiscountAmount, c.issueStartAt, c.issueEndAt, c.usableFrom, c.usableUntil,
			c.totalQuantity,
			case
			  when c.totalQuantity is null then true
			  when c.issueStrategy in (
			    kr.kro.airbob.domain.coupon.common.CouponIssueStrategy.SKIP_SURROGATE_RR,
			    kr.kro.airbob.domain.coupon.common.CouponIssueStrategy.SKIP_SURROGATE_RC)
			    then exists (select u.id from CouponIssueUnit u where u.couponId = c.id)
			  when c.issueStrategy in (
			    kr.kro.airbob.domain.coupon.common.CouponIssueStrategy.SKIP_COMPOSITE_RR,
			    kr.kro.airbob.domain.coupon.common.CouponIssueStrategy.SKIP_COMPOSITE_RC)
			    then exists (select u.id.couponId from CouponIssueUnitComposite u where u.id.couponId = c.id)
			  else (select count(mc.id) from MemberCoupon mc where mc.coupon = c) < c.totalQuantity
			end)
		from Coupon c
		where c.isActive = true
		  and c.stockPreparedAt is not null
		  and c.issueEndAt > :now
		order by c.issueStartAt desc, c.id desc
		""")
	List<CouponCampaignAvailabilityProjection> findCampaignsWithRemainingUnits(@Param("now") LocalDateTime now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Coupon c where c.id = :id")
	Optional<Coupon> findByIdForUpdate(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select c from Coupon c where c.id = :id")
	Optional<Coupon> findByIdForShare(@Param("id") Long id);

	@Query(value = "select * from coupon where id = :id for share nowait", nativeQuery = true)
	Optional<Coupon> findByIdForShareNowait(@Param("id") Long id);

	/**
	 * DB 발급의 재고·기간·활성 상태·경로 검증과 수량 증가를 한 UPDATE로 수행한다.
	 * unit 준비를 완료한 쿠폰은 이 경로에서 발급하지 않는다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
		update Coupon c
		set c.issuedQuantity = c.issuedQuantity + 1
		where c.id = :id
		  and c.stockPreparedAt is null
		  and c.isActive = true
		  and c.issueStartAt <= :now
		  and c.issueEndAt > :now
		  and (c.totalQuantity is null or c.issuedQuantity < c.totalQuantity)
		""")
	int incrementIssuedQuantityIfIssuable(@Param("id") Long id, @Param("now") LocalDateTime now);
}
