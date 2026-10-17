package kr.kro.airbob.domain.accommodation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.search.document.AccommodationDocument;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AddressResponse {
	@Builder
	@Schema(name = "AddressResponseAddressInfo")
	public record AddressInfo(
		@Schema(nullable = true) String country,
		@Schema(nullable = true) String state,
		@Schema(nullable = true) String city,
		@Schema(nullable = true) String district,
		@Schema(nullable = true) String street,
		@Schema(nullable = true) String detail,
		@Schema(nullable = true) String postalCode
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
	@Schema(name = "AddressResponseAddressSummaryInfo")
	public record AddressSummaryInfo(
		@Schema(nullable = true) String country,
		@Schema(nullable = true) String state,
		@Schema(nullable = true) String city,
		@Schema(nullable = true) String district
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
	@Schema(name = "AddressResponseCoordinate")
	public record Coordinate(
		@Schema(nullable = true) Double latitude,
		@Schema(nullable = true) Double longitude
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
