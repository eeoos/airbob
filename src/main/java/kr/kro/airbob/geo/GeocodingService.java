package kr.kro.airbob.geo;

import kr.kro.airbob.geo.dto.GeocodeResult;

public interface GeocodingService {

	GeocodeResult getCoordinates(String address);

}
