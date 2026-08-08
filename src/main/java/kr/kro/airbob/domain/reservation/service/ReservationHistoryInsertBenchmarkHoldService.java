package kr.kro.airbob.domain.reservation.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@Primary
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class ReservationHistoryInsertBenchmarkHoldService extends ReservationHoldService {

	private final ThreadLocal<List<HoldRemoval>> recordings = new ThreadLocal<>();

	public ReservationHistoryInsertBenchmarkHoldService(RedisTemplate<String, String> redisTemplate) {
		super(redisTemplate);
	}

	public void startRecording() {
		if (recordings.get() != null) {
			throw new IllegalStateException("hold removal recording is already active");
		}
		recordings.set(new ArrayList<>());
	}

	@Override
	public void removeHold(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
		List<HoldRemoval> activeRecordings = recordings.get();
		if (activeRecordings != null) {
			activeRecordings.add(new HoldRemoval(accommodationId, checkIn, checkOut));
			return;
		}
		super.removeHold(accommodationId, checkIn, checkOut);
	}

	public HoldRemovalSnapshot finishRecording() {
		List<HoldRemoval> activeRecordings = recordings.get();
		if (activeRecordings == null) {
			throw new IllegalStateException("hold removal recording is not active");
		}
		try {
			return new HoldRemovalSnapshot(activeRecordings);
		} finally {
			recordings.remove();
		}
	}

	public void clearRecording() {
		recordings.remove();
	}

	public record HoldRemoval(Long accommodationId, LocalDate checkIn, LocalDate checkOut) {
	}

	public record HoldRemovalSnapshot(List<HoldRemoval> removals) {
		public HoldRemovalSnapshot {
			removals = List.copyOf(removals);
		}

		public int callCount() {
			return removals.size();
		}
	}
}
