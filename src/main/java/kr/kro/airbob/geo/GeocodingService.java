package kr.kro.airbob.geo;

import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AddressRequest;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.geo.dto.GeocodeResult;

public interface GeocodingService {

	GeocodeResult getCoordinates(String address);

	String buildAddressString(AddressRequest.AddressInfo addressInfo);
}
