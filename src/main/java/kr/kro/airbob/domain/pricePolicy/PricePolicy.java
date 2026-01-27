package kr.kro.airbob.domain.pricePolicy;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PricePolicy extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private LocalDate startDate;
	private LocalDate endDate;
	private Integer price;
	private /*Season*/ String season;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "accommodation_id")
	private Accommodation accommodation;

}
