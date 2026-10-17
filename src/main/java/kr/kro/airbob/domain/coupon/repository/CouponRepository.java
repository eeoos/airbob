package kr.kro.airbob.domain.coupon.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.repository.projection.CouponCampaignProjection;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

	@Query(value = "select utc_timestamp(6)", nativeQuery = true)
	LocalDateTime findDatabaseUtcNow();

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

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Coupon c where c.id = :id")
	Optional<Coupon> findByIdForUpdate(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select c from Coupon c where c.id = :id")
	Optional<Coupon> findByIdForShare(@Param("id") Long id);

	@Query(value = "select * from coupon where id = :id for share nowait", nativeQuery = true)
	Optional<Coupon> findByIdForShareNowait(@Param("id") Long id);

}
