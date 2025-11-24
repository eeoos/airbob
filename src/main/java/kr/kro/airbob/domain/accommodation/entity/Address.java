package kr.kro.airbob.domain.accommodation.entity;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.accommodation.dto.AddressRequest;
import kr.kro.airbob.geo.dto.GeocodeResult;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

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
	private String state;
	private String city;
	private String district;
	private String street;
	private String detail;
	private String postalCode;

	private Double latitude;
	private Double longitude;

	public static Address createAddress(AddressRequest.AddressInfo addressInfo, GeocodeResult geocodeResult) {

		return Address.builder()
			.country(addressInfo.country())
			.state(addressInfo.state())
			.city(addressInfo.city())
			.district(addressInfo.district())
			.street(addressInfo.street())
			.detail(addressInfo.detail())
			.postalCode(addressInfo.postalCode())
			.latitude(geocodeResult.success() ? geocodeResult.latitude() : null)
			.longitude(geocodeResult.success() ? geocodeResult.longitude() : null)
			.build();
	}

	public boolean isChanged(AddressRequest.AddressInfo newAddressInfo) {
		return !Objects.equals(this.country, newAddressInfo.country()) ||
			!Objects.equals(this.state, newAddressInfo.state()) ||
			!Objects.equals(this.city, newAddressInfo.city()) ||
			!Objects.equals(this.district, newAddressInfo.district()) ||
			!Objects.equals(this.street, newAddressInfo.street()) ||
			!Objects.equals(this.detail, newAddressInfo.detail()) ||
			!Objects.equals(this.postalCode, newAddressInfo.postalCode());
	}

	public String buildFullAddress() {
		return Stream.of(this.getCountry(), this.getState(), this.getCity(), this.getDistrict(), this.getStreet(), this.getDetail())
			.filter(s -> s != null && !s.isBlank())
			.collect(Collectors.joining(" "));
	}

	public String buildAddressSummary() {
		return Stream.of(this.getDistrict(), this.getStreet(), this.getDetail())
			.filter(s -> s != null && !s.isBlank())
			.collect(Collectors.joining(" "));
	}
}
