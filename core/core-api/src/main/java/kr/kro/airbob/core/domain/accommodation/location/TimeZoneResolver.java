package kr.kro.airbob.core.domain.accommodation.location;

import java.time.ZoneId;
import java.util.Optional;

public interface TimeZoneResolver {

	Optional<ZoneId> resolve(double latitude, double longitude);
}
