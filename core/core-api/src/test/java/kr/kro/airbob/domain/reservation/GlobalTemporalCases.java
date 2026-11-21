package kr.kro.airbob.domain.reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.stream.Stream;

/** Fixed independent expectations; never derive the expected UTC values with the generator or ZoneId. */
public final class GlobalTemporalCases {

	private GlobalTemporalCases() {
	}

	public static Stream<Stay> stays() {
		return Stream.of(
			stay("vancouver-permanent-minus-seven", "America/Vancouver", "2026-11-15T07:30:00Z",
				"2026-11-15", "2027-02-15", "2027-02-22", "2026-11-15", "15:00", "2026-11-16", "11:00",
				"2026-11-15T22:00:00Z", "2026-11-16T18:00:00Z", 1, 20),
			stay("seoul-local-today", "Asia/Seoul", "2026-08-12T01:00:00Z",
				"2026-08-12", "2026-11-12", "2026-11-19", "2026-08-12", "15:00", "2026-08-13", "11:00",
				"2026-08-12T06:00:00Z", "2026-08-13T02:00:00Z", 1, 20),
			stay("new-york-previous-local-date", "America/New_York", "2026-08-12T01:00:00Z",
				"2026-08-11", "2026-11-11", "2026-11-18", "2026-08-12", "15:00", "2026-08-13", "11:00",
				"2026-08-12T19:00:00Z", "2026-08-13T15:00:00Z", 1, 20),
			stay("new-york-overlap-first-offset", "America/New_York", "2026-10-20T12:00:00Z",
				"2026-10-20", "2027-01-20", "2027-01-27", "2026-11-01", "01:30", "2026-11-02", "11:00",
				"2026-11-01T05:30:00Z", "2026-11-02T16:00:00Z", 1, 34.5),
			stay("chicago-fall-two-local-nights", "America/Chicago", "2016-11-01T12:00:00Z",
				"2016-11-01", "2017-02-01", "2017-02-08", "2016-11-04", "15:00", "2016-11-06", "11:00",
				"2016-11-04T20:00:00Z", "2016-11-06T17:00:00Z", 2, 45),
			stay("sydney-southern-fall", "Australia/Sydney", "2026-04-01T00:00:00Z",
				"2026-04-01", "2026-07-01", "2026-07-08", "2026-04-04", "15:00", "2026-04-06", "11:00",
				"2026-04-04T04:00:00Z", "2026-04-06T01:00:00Z", 2, 45),
			stay("sydney-southern-spring", "Australia/Sydney", "2026-10-01T00:00:00Z",
				"2026-10-01", "2027-01-01", "2027-01-08", "2026-10-03", "15:00", "2026-10-05", "11:00",
				"2026-10-03T05:00:00Z", "2026-10-05T00:00:00Z", 2, 43),
			stay("adelaide-summer-half-hour", "Australia/Adelaide", "2026-01-10T00:00:00Z",
				"2026-01-10", "2026-04-10", "2026-04-17", "2026-01-15", "15:00", "2026-01-16", "11:00",
				"2026-01-15T04:30:00Z", "2026-01-16T00:30:00Z", 1, 20),
			stay("adelaide-winter-half-hour", "Australia/Adelaide", "2026-07-10T00:00:00Z",
				"2026-07-10", "2026-10-10", "2026-10-17", "2026-07-15", "15:00", "2026-07-16", "11:00",
				"2026-07-15T05:30:00Z", "2026-07-16T01:30:00Z", 1, 20),
			stay("month-end-calendar-three-months", "Asia/Seoul", "2026-01-31T00:00:00Z",
				"2026-01-31", "2026-04-30", "2026-05-07", "2026-04-29", "15:00", "2026-04-30", "11:00",
				"2026-04-29T06:00:00Z", "2026-04-30T02:00:00Z", 1, 20),
			stay("leap-year-three-month-boundary", "Asia/Seoul", "2023-11-30T00:00:00Z",
				"2023-11-30", "2024-02-29", "2024-03-07", "2024-02-28", "15:00", "2024-02-29", "11:00",
				"2024-02-28T06:00:00Z", "2024-02-29T02:00:00Z", 1, 20)
		);
	}

	private static Stay stay(String label, String zone, String now, String today, String bookingEnd,
		String seedEnd, String checkIn, String checkInTime, String checkOut, String checkOutTime,
		String checkInAt, String checkOutAt, long nights, double elapsedHours) {
		return new Stay(label, zone, Instant.parse(now), LocalDate.parse(today), LocalDate.parse(bookingEnd),
			LocalDate.parse(seedEnd), LocalDate.parse(checkIn), LocalTime.parse(checkInTime),
			LocalDate.parse(checkOut), LocalTime.parse(checkOutTime), Instant.parse(checkInAt),
			Instant.parse(checkOutAt), nights, elapsedHours);
	}

	public record Stay(String label, String zone, Instant decisionAt, LocalDate localToday,
		LocalDate bookingEnd, LocalDate seedEnd, LocalDate checkIn, LocalTime checkInTime,
		LocalDate checkOut, LocalTime checkOutTime, Instant checkInAt, Instant checkOutAt,
		long nights, double elapsedHours) {
		@Override
		public String toString() {
			return label;
		}
	}
}
