package kr.kro.airbob.geo;

import java.time.ZoneId;
import java.util.Optional;

public interface TimeZoneResolver {

	Optional<ZoneId> resolve(double latitude, double longitude);
}
