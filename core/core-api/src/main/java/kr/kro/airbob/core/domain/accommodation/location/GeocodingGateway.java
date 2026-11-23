package kr.kro.airbob.core.domain.accommodation.location;

public interface GeocodingGateway {

	GeocodeResult getCoordinates(String address);

}
