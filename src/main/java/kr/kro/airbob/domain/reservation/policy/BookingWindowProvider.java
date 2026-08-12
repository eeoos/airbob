package kr.kro.airbob.domain.reservation.policy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

@Component
public class BookingWindowProvider {

	private final Clock clock;

	public BookingWindowProvider(Clock clock) {
		this.clock = clock;
	}

	public BookingWindow currentFor(String timeZoneId) {
		ZoneId zone = ZoneId.of(timeZoneId);
		LocalDate localToday = Instant.now(clock).atZone(zone).toLocalDate();
		return BookingWindow.startingOn(localToday);
	}
}
