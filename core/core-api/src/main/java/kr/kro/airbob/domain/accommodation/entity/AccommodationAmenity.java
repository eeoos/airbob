package kr.kro.airbob.domain.accommodation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import kr.kro.airbob.common.domain.BaseEntity;
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
public class AccommodationAmenity extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Integer count;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "accommodation_id")
	private Accommodation accommodation;

	// 공통 코드(AMENITY_TYPE)의 code 값. FK 없이 느슨하게 보관, 검증은 CommonCodeService.
	@Column(name = "amenity_code", length = 50)
	private String amenityCode;

	public static AccommodationAmenity createAccommodationAmenity(Accommodation accommodation, String amenityCode, int count) {
		return AccommodationAmenity.builder()
				.accommodation(accommodation)
				.amenityCode(amenityCode)
				.count(count)
				.build();
	}

}
