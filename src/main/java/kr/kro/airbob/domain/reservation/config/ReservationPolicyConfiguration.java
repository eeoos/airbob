package kr.kro.airbob.domain.reservation.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kr.kro.airbob.domain.reservation.policy.ReservationHoldPolicy;
import kr.kro.airbob.domain.reservation.policy.ReservationPaymentAttemptPolicy;
import kr.kro.airbob.domain.reservation.policy.ReservationQuotePolicy;

@Configuration(proxyBeanMethods = false)
public class ReservationPolicyConfiguration {

	@Bean
	public ReservationHoldPolicy reservationHoldPolicy(
		@Value("${reservation.hold.duration}") Duration duration
	) {
		return new ReservationHoldPolicy(duration);
	}

	@Bean
	public ReservationQuotePolicy reservationQuotePolicy(
		@Value("${reservation.quote.duration}") Duration duration
	) {
		return new ReservationQuotePolicy(duration);
	}

	@Bean
	public ReservationPaymentAttemptPolicy reservationPaymentAttemptPolicy(
		@Value("${reservation.payment-attempt.minimum-remaining}") Duration minimumRemaining,
		@Value("${reservation.hold.duration}") Duration holdDuration
	) {
		if (minimumRemaining.compareTo(holdDuration) >= 0) {
			throw new IllegalArgumentException(
				"minimum payment-attempt remaining time must be shorter than reservation hold duration");
		}
		return new ReservationPaymentAttemptPolicy(minimumRemaining);
	}
}
