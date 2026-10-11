package kr.kro.airbob.domain.coupon.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.coupon.entity.CouponIssueUnitComposite;
import kr.kro.airbob.domain.coupon.repository.projection.CouponIssueUnitProjection;

public interface CouponIssueUnitCompositeRepository extends JpaRepository<CouponIssueUnitComposite, CouponIssueUnitComposite.Id> {
	@Query(value = """
		select unit_no as unitKey, unit_no as unitNumber from coupon_issue_unit_composite
		where coupon_id = :couponId order by unit_no limit 1 for update skip locked
		""", nativeQuery = true)
	Optional<CouponIssueUnitProjection> lockAvailable(@Param("couponId") long couponId);

	@Modifying
	@Query(value = "delete from coupon_issue_unit_composite where coupon_id = :couponId and unit_no = :unitKey", nativeQuery = true)
	int consume(@Param("couponId") long couponId, @Param("unitKey") long unitKey);

	@Query(value = "select unit_no from coupon_issue_unit_composite where coupon_id = :couponId limit 1", nativeQuery = true)
	Optional<Integer> findRemainingUnit(@Param("couponId") long couponId);

	@Query(value = "select count(*) from coupon_issue_unit_composite where coupon_id = :couponId", nativeQuery = true)
	long countUnits(@Param("couponId") long couponId);

	@Modifying
	@Query(value = "delete from coupon_issue_unit_composite where coupon_id = :couponId and unit_no > :target", nativeQuery = true)
	int removeAbove(@Param("couponId") long couponId, @Param("target") int target);
}
