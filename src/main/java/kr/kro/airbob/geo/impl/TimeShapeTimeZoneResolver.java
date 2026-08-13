package kr.kro.airbob.geo.impl;

import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import kr.kro.airbob.geo.TimeZoneResolver;
import net.iakovlev.timeshape.TimeZoneEngine;

@Component
public final class TimeShapeTimeZoneResolver implements TimeZoneResolver {

	private final TimeZoneEngine engine;

	public TimeShapeTimeZoneResolver() {
		this(EngineHolder.INSTANCE);
	}

	TimeShapeTimeZoneResolver(TimeZoneEngine engine) {
		this.engine = Objects.requireNonNull(engine);
	}

	@Override
	public Optional<ZoneId> resolve(double latitude, double longitude) {
		if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
			|| latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
			return Optional.empty();
		}

		return engine.query(latitude, longitude);
	}

	private static final class EngineHolder {

		private static final TimeZoneEngine INSTANCE = TimeZoneEngine.initialize();
	}
}
