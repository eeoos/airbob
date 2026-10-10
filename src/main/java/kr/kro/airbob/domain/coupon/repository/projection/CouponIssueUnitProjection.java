package kr.kro.airbob.domain.coupon.repository.projection;

/** Scalar result: selecting a unit does not retain a managed entity after its native DELETE. */
public interface CouponIssueUnitProjection {
	Long getUnitKey();
	Integer getUnitNumber();
}
