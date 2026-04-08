package kr.kro.airbob.domain.reservation.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReservationHoldService {

	private final RedisTemplate<String, String> redisTemplate;
	private static final String HOLD_KEY_PREFIX = "HOLD:RESERVATION:";
	private static final long HOLD_DURATION_SECONDS = 15 * 60L; // 15분

	public void holdDates(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
		List<String> holdKeys = generateHoldKeys(accommodationId, checkIn, checkOut);

		// SET EX를 파이프라인으로 묶어 MSET+TTL의 non-atomic 문제 해결
		redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
			for (String key : holdKeys) {
				connection.stringCommands().set(
					key.getBytes(),
					"held".getBytes(),
					org.springframework.data.redis.core.types.Expiration.seconds(HOLD_DURATION_SECONDS),
					org.springframework.data.redis.connection.RedisStringCommands.SetOption.UPSERT
				);
			}
			return null;
		});
	}

	public boolean isAnyDateHeld(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
		List<String> holdKeys = generateHoldKeys(accommodationId, checkIn, checkOut);

		// MGET 사용
		List<String> results = redisTemplate.opsForValue().multiGet(holdKeys);

		return results != null && results.stream().anyMatch(result -> result != null);
	}

	public void removeHold(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
		List<String> holdKeys = generateHoldKeys(accommodationId, checkIn, checkOut);
		redisTemplate.delete(holdKeys);
	}

	private List<String> generateHoldKeys(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
		return checkIn.datesUntil(checkOut)
			.map(date -> HOLD_KEY_PREFIX + accommodationId + ":" + date)
			.toList();
	}

}
