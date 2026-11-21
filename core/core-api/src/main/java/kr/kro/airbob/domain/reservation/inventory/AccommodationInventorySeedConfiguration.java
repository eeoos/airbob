package kr.kro.airbob.domain.reservation.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;

@Configuration(proxyBeanMethods = false)
public class AccommodationInventorySeedConfiguration {

	@Bean
	AccommodationInventorySeedPolicy accommodationInventorySeedPolicy(
		BookingWindowProvider bookingWindowProvider,
		@Value("${reservation.inventory.seed.safety-buffer-days:7}") int safetyBufferDays
	) {
		return new AccommodationInventorySeedPolicy(bookingWindowProvider, safetyBufferDays);
	}
}
