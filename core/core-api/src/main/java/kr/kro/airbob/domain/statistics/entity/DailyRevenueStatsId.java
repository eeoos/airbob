package kr.kro.airbob.domain.statistics.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

// DailyRevenueStats 복합키 (stat_date, accommodation_id)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class DailyRevenueStatsId implements Serializable {

	private LocalDate statDate;
	private Long accommodationId;
}
