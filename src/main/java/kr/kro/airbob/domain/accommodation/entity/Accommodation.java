package kr.kro.airbob.domain.accommodation.entity;

import java.time.LocalTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.accommodation.common.AccommodationType;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.UpdateAccommodationDto;
import kr.kro.airbob.domain.member.Member;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Accommodation extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(nullable = false, unique = true, updatable = false, columnDefinition = "BINARY(16)")
	private UUID accommodationUid;

	private String name;

	private String description;

	private Integer basePrice;

	private String thumbnailUrl;

	@Enumerated(EnumType.STRING)
	private AccommodationType type;

	@OneToOne(fetch = FetchType.LAZY, orphanRemoval = true)
	private Address address;

	@OneToOne(fetch = FetchType.LAZY, orphanRemoval = true)
	private OccupancyPolicy occupancyPolicy;

	@OneToOne(fetch = FetchType.LAZY)
	private Member member;

	@Column(nullable = false)
	private LocalTime checkInTime;

	@Column(nullable = false)
	private LocalTime checkOutTime;

	public static Accommodation createAccommodation(AccommodationRequest.CreateAccommodationDto request,
													Address address, OccupancyPolicy occupancyPolicy, Member member) {
		return Accommodation.builder()
			.name(request.getName())
			.description(request.getDescription())
			.basePrice(request.getBasePrice())
			.thumbnailUrl(request.getThumbnailUrl())
			.type(AccommodationType.valueOf(request.getType()))
			.address(address)
			.occupancyPolicy(occupancyPolicy)
			.member(member)
			.checkInTime(request.getCheckInTime())
			.checkOutTime(request.getCheckOutTime())
			.build();
	}

	public void updateAccommodation(UpdateAccommodationDto request) {
		if (request.getName() != null) {
			this.name = request.getName();
		}

		if (request.getDescription() != null) {
			this.description = request.getDescription();
		}

		if (request.getBasePrice() != null) {
			this.basePrice = request.getBasePrice();
		}

		if (request.getType() != null) {
			this.type = AccommodationType.valueOf(request.getType().toUpperCase());
		}
	}

	public void updateAddress(Address newAddress) {
		this.address = newAddress;
	}

	public void updateOccupancyPolicy(OccupancyPolicy occupancyPolicy) {
		this.occupancyPolicy = occupancyPolicy;
	}
}
