package kr.kro.airbob.client.google;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
public class GoogleGeocodeResponse {

	private String status;
	private List<Result> results;

	public record Result(
		Geometry geometry) {
	}

	public record Geometry(
		Location location,
		Viewport viewport) {
		public record Viewport(
			Location northeast,
			Location southwest) {
		}

		@Builder
		public record Location(
			Double lat,
			Double lng

		) {
		}
	}
}
