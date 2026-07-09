package kr.kro.airbob.domain.coupon.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.member.entity.Member;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 발급된 쿠폰(1인 1매). {@code UNIQUE(member_id, coupon_id)} - 중복 발급의 최후 방어선
 */
@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
	name = "member_coupon",
	uniqueConstraints = @UniqueConstraint(name = "uk_member_coupon", columnNames = {"member_id", "coupon_id"})
)
public class MemberCoupon extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "coupon_id", nullable = false)
	private Coupon coupon;

	@Column(nullable = false)
	private boolean used;

	private LocalDateTime usedAt;

	// 사용된 예약 (사용 시점에 연결, nullable)
	private Long reservationId;

	public static MemberCoupon issue(Member member, Coupon coupon) {
		return MemberCoupon.builder()
			.member(member)
			.coupon(coupon)
			.used(false)
			.build();
	}
}
