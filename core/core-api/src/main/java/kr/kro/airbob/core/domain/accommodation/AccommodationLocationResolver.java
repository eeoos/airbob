package kr.kro.airbob.core.domain.accommodation;

import java.time.ZoneId;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.location.GeocodeResult;
import kr.kro.airbob.core.domain.accommodation.location.GeocodingGateway;
import kr.kro.airbob.core.domain.accommodation.location.TimeZoneResolver;
import lombok.RequiredArgsConstructor;

/** 숙소 주소를 좌표와 시간대로 바꾼다. */
@Component
@RequiredArgsConstructor
public class AccommodationLocationResolver {

	private final GeocodingGateway geocodingGateway;
	private final TimeZoneResolver timeZoneResolver;

	/**
	 * 새 주소의 위치. 새 주소가 없거나, 지금 주소와 같고 시간대가 이미 있으면 null(바꿀 필요 없음).
	 * 좌표나 시간대를 찾지 못하면 AccommodationLocationResolutionException.
	 */
	public ResolvedLocation resolve(Accommodation accommodation, NewAddress newAddress) {
		if (newAddress == null) {
			return null;
		}

		Address currentAddress = accommodation.getAddress();
		if (currentAddress != null
			&& !currentAddress.isChanged(newAddress)
			&& accommodation.getTimeZoneId() != null) {
			return null;
		}

		String addressStr = Address.buildAddressStringForGeocoding(newAddress);
		GeocodeResult geocodeResult = geocodingGateway.getCoordinates(addressStr);
		if (geocodeResult == null
			|| !geocodeResult.success()
			|| geocodeResult.latitude() == null
			|| geocodeResult.longitude() == null) {
			throw new AccommodationLocationResolutionException();
		}

		ZoneId timeZone = timeZoneResolver.resolve(geocodeResult.latitude(), geocodeResult.longitude())
			.orElseThrow(AccommodationLocationResolutionException::new);
		return new ResolvedLocation(
			Address.createAddress(newAddress, geocodeResult.latitude(), geocodeResult.longitude()), timeZone);
	}

	/** 아직 저장하지 않은 주소와 그 시간대. */
	public record ResolvedLocation(Address address, ZoneId timeZone) {
	}
}
