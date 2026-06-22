package kr.kro.airbob.domain.review.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccommodationReviewSummary extends BaseEntity {

	@Id
	@Column(name = "accommodation_id")
	private Long accommodationId;

	@OneToOne(fetch = FetchType.LAZY)
	@MapsId
	@JoinColumn(name = "accommodation_id")
	private Accommodation accommodation;

	@Column(nullable = false)
	@Builder.Default
	private Integer totalReviewCount = 0;

	@Column(nullable = false)
	@Builder.Default
	private Long ratingSum = 0L;

	@Column(precision = 3, scale = 2)
	@Builder.Default
	private BigDecimal averageRating = BigDecimal.ZERO;

	// 집계 갱신은 in-memory 변이 + 낙관적 락 대신 AccommodationReviewSummaryRepository의
	// 원자적 SQL(INSERT ... ON DUPLICATE KEY UPDATE / 원자적 UPDATE)로 수행한다.
	// 이 엔티티는 읽기/매핑 용도로만 사용.
}
