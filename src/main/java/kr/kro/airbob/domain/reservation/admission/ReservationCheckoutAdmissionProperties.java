package kr.kro.airbob.domain.reservation.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reservation.checkout.admission")
public record ReservationCheckoutAdmissionProperties(int maxConcurrency) {

	public ReservationCheckoutAdmissionProperties {
		if (maxConcurrency <= 0) {
			throw new IllegalArgumentException(
				"checkout admission maxConcurrency must be positive");
		}
	}
}
