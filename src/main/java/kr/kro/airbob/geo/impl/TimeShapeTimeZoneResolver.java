package kr.kro.airbob.geo.impl;

import java.time.ZoneId;
import java.util.Optional;

import org.springframework.stereotype.Component;

import kr.kro.airbob.geo.TimeZoneResolver;
import net.iakovlev.timeshape.TimeZoneEngine;

@Component
public final class TimeShapeTimeZoneResolver implements TimeZoneResolver {

	private final TimeZoneEngine engine = TimeZoneEngine.initialize();

	@Override
	public Optional<ZoneId> resolve(double latitude, double longitude) {
		if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
			return Optional.empty();
		}

		return engine.query(latitude, longitude);
	}
}
