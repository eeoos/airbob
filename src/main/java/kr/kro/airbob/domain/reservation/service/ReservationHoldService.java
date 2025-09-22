package kr.kro.airbob.domain.reservation.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReservationHoldService {

	private final RedisTemplate<String, String> redisTemplate;
	private static final String HOLD_KEY_PREFIX = "HOLD:RESERVATION";
	private static final Duration HOLD_DURATION = Duration.ofMinutes(15); // 15분

	public void holdDates(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
		List<String> holdKeys = generateHoldKeys(accommodationId, checkIn, checkOut);

		// MSET 사용
		redisTemplate.opsForValue().multiSet(
			holdKeys.stream().collect(Collectors.toMap(key -> key,  key -> "held"))
		);

		// TTL 설정
		holdKeys.forEach(key -> redisTemplate.expire(key, HOLD_DURATION));
	}

	public boolean isAnyDateHeld(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
		List<String> holdKeys = generateHoldKeys(accommodationId, checkIn, checkOut);

		// MGET 사용
		List<String> results = redisTemplate.opsForValue().multiGet(holdKeys);

		return results != null && results.stream().anyMatch(result -> result != null);
	}

	private List<String> generateHoldKeys(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
		return checkIn.datesUntil(checkOut)
			.map(date -> HOLD_KEY_PREFIX + accommodationId + ":" + date)
			.toList();
	}

}
