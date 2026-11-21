package kr.kro.airbob.geo;

import static kr.kro.airbob.geo.dto.GoogleGeocodeResponse.Geometry.*;

import org.springframework.stereotype.Component;

import kr.kro.airbob.geo.dto.Coordinate;

@Component
public class ViewportAdjuster {

	private static final double MIN_RADIUS_METERS = 3000; // 3km
	private static final double ONE_DEGREE = 111.0; // 위도 1도 ≈ 111km
	private static final double MAX_LATITUDE = 85.0; // Google Maps 위도 한계
	private static final double MIN_LATITUDE = -85.0; // Google Maps 위도 한계

	public Viewport adjustViewportIfSmall(Viewport originalViewport) {
		if (originalViewport == null) {
			return null;
		}

		double currentRadius = calculateViewportRadius(originalViewport);

		if (currentRadius < MIN_RADIUS_METERS) {
			return expandViewport(originalViewport);
		}

		return originalViewport;
	}

	public Viewport createViewportFromCenter(double centerLat, double centerLng) {
		double radiusKm = MIN_RADIUS_METERS / 1000.0;
		double latDelta = radiusKm / ONE_DEGREE;
		double lngDelta = radiusKm / (ONE_DEGREE * Math.cos(Math.toRadians(centerLat)));

		// Google Maps 범위를 벗어나지 않도록 제한
		double topLat = Math.min(MAX_LATITUDE, centerLat + latDelta);
		double bottomLat = Math.max(MIN_LATITUDE, centerLat - latDelta);
		double rightLng = normalizeLongitude(centerLng + lngDelta);
		double leftLng = normalizeLongitude(centerLng - lngDelta);

		Location northeast = Location.builder()
			.lat(topLat)
			.lng(rightLng)
			.build();

		Location southwest = Location.builder()
			.lat(bottomLat)
			.lng(leftLng)
			.build();

		return new Viewport(northeast, southwest);
	}

	private double calculateViewportRadius(Viewport viewport) {
		double distance = calculateDistance(viewport.northeast(), viewport.southwest());
		return distance / 2.0;
	}

	private double calculateDistance(Location p1, Location p2) {
		// m
		double latDiff = p1.lat() - p2.lat();
		double lngDiff = p1.lng() - p2.lng();

		// km
		double latDistance = Math.abs(latDiff) * ONE_DEGREE;
		double lngDistance = Math.abs(lngDiff) * ONE_DEGREE * Math.cos(Math.toRadians(p1.lat()));

		return Math.sqrt(latDistance * latDistance + lngDistance * lngDistance) * 1000; // m
	}

	private Coordinate calculateCenter(Viewport viewport) {
		double centerLat = (viewport.northeast().lat() + viewport.southwest().lat()) / 2.0;
		double centerLng = (viewport.northeast().lng() + viewport.southwest().lng()) / 2.0;
		return Coordinate.builder()
			.latitude(centerLat)
			.longitude(centerLng)
			.build();
	}

	private Viewport expandViewport(Viewport originalViewport) {
		Coordinate center = calculateCenter(originalViewport);

		return createViewportFromCenter(
			center.latitude(),
			center.longitude()
		);
	}

	private double normalizeLongitude(double lng) {
		while (lng > 180.0) lng -= 360.0;
		while (lng < -180.0) lng += 360.0;
		return lng;
	}
}


