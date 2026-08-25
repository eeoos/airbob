package kr.kro.airbob.domain.reservation.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kr.kro.airbob.domain.reservation.policy.ReservationHoldPolicy;

@Configuration(proxyBeanMethods = false)
public class ReservationPolicyConfiguration {

	@Bean
	public ReservationHoldPolicy reservationHoldPolicy(
		@Value("${reservation.hold.duration}") Duration duration
	) {
		return new ReservationHoldPolicy(duration);
	}
}
