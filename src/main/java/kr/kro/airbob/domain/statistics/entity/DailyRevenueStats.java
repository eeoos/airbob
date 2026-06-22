package kr.kro.airbob.domain.statistics.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import kr.kro.airbob.common.domain.BaseEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// 일일 매출 사전집계 (stat_date, accommodation_id). 쓰기는 배치의 네이티브 INSERT...SELECT로 수행되고,
// JPA는 주로 읽기/매핑 용도로 사용한다.
@Entity
@Table(name = "daily_revenue_stats")
@IdClass(DailyRevenueStatsId.class)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyRevenueStats extends BaseEntity {

	@Id
	@Column(name = "stat_date")
	private LocalDate statDate;

	@Id
	@Column(name = "accommodation_id")
	private Long accommodationId;

	@Column(nullable = false)
	private Long grossAmount;

	@Column(nullable = false)
	private Long refundAmount;

	@Column(nullable = false)
	private Long netAmount;

	@Column(nullable = false)
	private Integer paymentCount;

	@Column(nullable = false)
	private Integer refundCount;
}
