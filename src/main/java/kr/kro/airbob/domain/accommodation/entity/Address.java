package kr.kro.airbob.domain.accommodation.entity;

import java.util.Objects;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.geo.dto.GeocodeResult;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String country;
	private String city;
	private String district;
	private String street;
	private String detail;
	private String postalCode;
	private Double latitude;
	private Double longitude;

	public static Address createAddress(AccommodationRequest.AddressInfo addressInfo) {

		return Address.builder()
			.country(addressInfo.country())
			.city(addressInfo.city())
			.district(addressInfo.district())
			.street(addressInfo.street())
			.detail(addressInfo.detail())
			.postalCode(addressInfo.postalCode())
			.latitude(addressInfo.latitude())
			.longitude(addressInfo.longitude())
			.build();
	}

	public boolean isChanged(AccommodationRequest.AddressInfo newAddressInfo) {
		return !Objects.equals(this.country, newAddressInfo.country()) ||
			!Objects.equals(this.city, newAddressInfo.city()) ||
			!Objects.equals(this.district, newAddressInfo.district()) ||
			!Objects.equals(this.street, newAddressInfo.street()) ||
			!Objects.equals(this.detail, newAddressInfo.detail()) ||
			!Objects.equals(this.postalCode, newAddressInfo.postalCode());
	}
}
