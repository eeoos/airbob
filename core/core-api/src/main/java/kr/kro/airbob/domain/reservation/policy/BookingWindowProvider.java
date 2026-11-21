package kr.kro.airbob.domain.reservation.policy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class BookingWindowProvider {
	private static final List<ZoneId> AVAILABLE_TIME_ZONES = ZoneId.getAvailableZoneIds().stream()
		.map(ZoneId::of)
		.toList();

	private final Clock clock;

	public BookingWindowProvider(Clock clock) {
		this.clock = clock;
	}

	public BookingWindow currentFor(String timeZoneId) {
		return currentFor(timeZoneId, clock.instant());
	}

	public BookingWindow currentFor(String timeZoneId, Instant now) {
		return currentFor(ZoneId.of(timeZoneId), now);
	}

	private BookingWindow currentFor(ZoneId timeZone, Instant now) {
		LocalDate localToday = now.atZone(timeZone).toLocalDate();
		return BookingWindow.startingOn(localToday);
	}

	public Set<String> eligibleTimeZonesForStay(LocalDate checkInDate, LocalDate checkOutDate) {
		Instant now = clock.instant();
		return AVAILABLE_TIME_ZONES.stream()
			.filter(timeZone -> currentFor(timeZone, now).containsStay(checkInDate, checkOutDate))
			.map(ZoneId::getId)
			.collect(Collectors.toUnmodifiableSet());
	}

	public ReservationIndexingWindow currentIndexingWindow() {
		LocalDate utcToday = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
		BookingWindow latestLocalBookingWindow = BookingWindow.startingOn(utcToday.plusDays(1));
		return new ReservationIndexingWindow(
			utcToday.minusDays(1),
			latestLocalBookingWindow.endExclusive()
		);
	}
}
