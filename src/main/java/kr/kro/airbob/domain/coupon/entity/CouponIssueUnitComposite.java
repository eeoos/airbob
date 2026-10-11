package kr.kro.airbob.domain.coupon.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "coupon_issue_unit_composite")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssueUnitComposite {
	@EmbeddedId
	private Id id;

	@Embeddable
	@Getter
	@EqualsAndHashCode
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class Id implements Serializable {
		@Column(name = "coupon_id", nullable = false)
		private Long couponId;

		@Column(name = "unit_no", nullable = false)
		private Integer unitNo;
	}
}
