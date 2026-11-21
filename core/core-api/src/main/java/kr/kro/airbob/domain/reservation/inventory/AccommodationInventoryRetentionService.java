package kr.kro.airbob.domain.reservation.inventory;

import java.time.LocalDate;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccommodationInventoryRetentionService {

	private final AccommodationInventoryDayRepository repository;

	public AccommodationInventoryRetentionService(
		AccommodationInventoryDayRepository repository
	) {
		this.repository = repository;
	}

	@Transactional
	public int deleteNextPastFreeBatch(LocalDate cutoffExclusive, int batchSize) {
		Objects.requireNonNull(cutoffExclusive, "inventory retention cutoff must not be null");
		if (batchSize < 1) {
			throw new IllegalArgumentException("inventory retention batch size must be positive");
		}
		return repository.deletePastFreeDays(cutoffExclusive, batchSize);
	}
}
