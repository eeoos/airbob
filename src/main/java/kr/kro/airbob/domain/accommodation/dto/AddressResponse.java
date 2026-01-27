package kr.kro.airbob.domain.accommodation.dto;

import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.search.document.AccommodationDocument;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AddressResponse {
	@Builder
	public record AddressInfo(
		String country,
		String state,
		String city,
		String district,
		String street,
		String detail,
		String postalCode
	) {
		public static AddressInfo from(Address address) {
			if(address == null) return AddressInfo.builder().build();
			return AddressInfo.builder()
				.country(address.getCountry())
				.state(address.getState())
				.city(address.getCity())
				.district(address.getDistrict())
				.street(address.getStreet())
				.detail(address.getDetail())
				.postalCode(address.getPostalCode())
				.build();
		}
	}

	@Builder
	public record AddressSummaryInfo(
		String country,
		String state,
		String city,
		String district
	) {
		public static AddressSummaryInfo from(Address address) {
			if(address == null) return AddressSummaryInfo.builder().build();
			return AddressSummaryInfo.builder()
				.country(address.getCountry())
				.state(address.getState())
				.city(address.getCity())
				.district(address.getDistrict())
				.build();
		}

		public static AddressSummaryInfo from(AccommodationDocument document) {
			return AddressSummaryInfo.builder()
				.country(document.country())
				.state(document.state())
				.city(document.city())
				.district(document.district())
				.build();
		}
	}

	@Builder
	public record Coordinate(
		Double latitude,
		Double longitude
	) {
		public static Coordinate from(Address address) {
			if(address == null) return Coordinate.builder().build();
			return Coordinate.builder()
				.latitude(address.getLatitude())
				.longitude(address.getLongitude())
				.build();
		}
	}
}
