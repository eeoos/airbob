package kr.kro.airbob.domain.reservation.service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

public class DateLockKeyGenerator {

	private static final String LOCK_KEY_PREFIX = "LOCK:RESERVATION:";

	public static List<String> generateLockKeys(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
		return checkIn.datesUntil(checkOut)
			.map(date -> LOCK_KEY_PREFIX + accommodationId + ":" + date)
			.sorted() // ** 데드락 방지를 위한 오름차순 **
			.collect(Collectors.toList());
	}
}
