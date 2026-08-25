package kr.kro.airbob.domain.reservation.inventory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;

@Service
public class AccommodationInventorySeedService {

	private final AccommodationRepository accommodationRepository;
	private final ReservationInventoryService inventoryService;
	private final AccommodationInventorySeedPolicy seedPolicy;
	private final Clock clock;

	public AccommodationInventorySeedService(
		AccommodationRepository accommodationRepository,
		ReservationInventoryService inventoryService,
		AccommodationInventorySeedPolicy seedPolicy,
		Clock clock
	) {
		this.accommodationRepository = accommodationRepository;
		this.inventoryService = inventoryService;
		this.seedPolicy = seedPolicy;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void seedCurrentHorizon(Accommodation accommodation) {
		Accommodation target = Objects.requireNonNull(accommodation, "accommodation must not be null");
		seed(target.getId(), target.getTimeZoneId(), clock.instant());
	}

	public SeedBatch seedNextPublishedBatch(long afterAccommodationId, int batchSize) {
		if (afterAccommodationId < 0) {
			throw new IllegalArgumentException("inventory seed cursor must not be negative");
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("inventory seed batch size must be positive");
		}
		List<AccommodationRepository.InventorySeedTarget> targets =
			accommodationRepository.findInventorySeedTargets(
				AccommodationStatus.PUBLISHED,
				afterAccommodationId,
				PageRequest.of(0, batchSize)
			);
		if (targets.isEmpty()) {
			return new SeedBatch(0, afterAccommodationId);
		}

		Instant decisionAt = clock.instant();
		for (AccommodationRepository.InventorySeedTarget target : targets) {
			seed(target.getAccommodationId(), target.getTimeZoneId(), decisionAt);
		}
		return new SeedBatch(
			targets.size(),
			targets.getLast().getAccommodationId()
		);
	}

	private void seed(Long accommodationId, String timeZoneId, Instant decisionAt) {
		if (accommodationId == null || accommodationId <= 0) {
			throw new IllegalArgumentException("inventory seed accommodationId must be positive");
		}
		AccommodationInventorySeedPolicy.SeedRange range =
			seedPolicy.currentRange(timeZoneId, decisionAt);
		inventoryService.seed(accommodationId, range.startInclusive(), range.endExclusive());
	}

	public record SeedBatch(int processed, long lastAccommodationId) {
		public SeedBatch {
			if (processed < 0 || lastAccommodationId < 0) {
				throw new IllegalArgumentException("inventory seed batch values must not be negative");
			}
		}
	}
}
