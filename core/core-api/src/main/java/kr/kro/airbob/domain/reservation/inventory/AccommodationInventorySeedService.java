package kr.kro.airbob.domain.reservation.inventory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.reservation.exception.ReservationInventoryNotReadyException;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventoryDayRepository.SeedDay;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventorySeedPolicy.SeedRange;

@Service
public class AccommodationInventorySeedService {

	private final AccommodationRepository accommodationRepository;
	private final ReservationInventoryService inventoryService;
	private final AccommodationInventoryDayRepository inventoryRepository;
	private final AccommodationInventorySeedPolicy seedPolicy;
	private final Clock clock;

	public AccommodationInventorySeedService(
		AccommodationRepository accommodationRepository,
		ReservationInventoryService inventoryService,
		AccommodationInventoryDayRepository inventoryRepository,
		AccommodationInventorySeedPolicy seedPolicy,
		Clock clock
	) {
		this.accommodationRepository = accommodationRepository;
		this.inventoryService = inventoryService;
		this.inventoryRepository = inventoryRepository;
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
		if (batchSize < 1 || batchSize > AccommodationInventoryDayRepository.MAX_SEED_TARGETS) {
			throw new IllegalArgumentException("inventory seed batch size must be between 1 and 1000");
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
		Map<SeedRange, List<Long>> idsByRange = new LinkedHashMap<>();
		long lastId = afterAccommodationId;
		for (AccommodationRepository.InventorySeedTarget target : targets) {
			Long id = target.getAccommodationId();
			if (id == null || id <= lastId) {
				throw new IllegalStateException("inventory seed targets must advance in primary-key order");
			}
			lastId = id;
			SeedRange range = seedPolicy.currentRange(target.getTimeZoneId(), decisionAt);
			idsByRange.computeIfAbsent(range, ignored -> new ArrayList<>()).add(id);
		}
		long expectedDays = 0;
		long missingDaysSubmitted = 0;
		int insertStatements = 0;
		for (Map.Entry<SeedRange, List<Long>> group : idsByRange.entrySet()) {
			SeedRange range = group.getKey();
			List<Long> ids = group.getValue();
			int nights = Math.toIntExact(ChronoUnit.DAYS.between(
				range.startInclusive(), range.endExclusive()));
			expectedDays += (long)ids.size() * nights;
			Map<Long, Integer> counts = inventoryRepository.countSeedDays(
				ids, range.startInclusive(), range.endExclusive());
			List<Long> incompleteIds = ids.stream()
				.filter(id -> counts.getOrDefault(id, 0) != nights).toList();
			if (incompleteIds.isEmpty()) {
				continue;
			}
			Set<SeedDay> existing = new HashSet<>(inventoryRepository.findSeedDays(
				incompleteIds, range.startInclusive(), range.endExclusive()));
			List<SeedDay> insertBatch = new ArrayList<>(
				AccommodationInventoryDayRepository.MAX_SEED_INSERT_ROWS);
			for (Long id : incompleteIds) {
				for (LocalDate date = range.startInclusive(); date.isBefore(range.endExclusive());
					date = date.plusDays(1)) {
					SeedDay candidate = new SeedDay(id, date);
					if (existing.contains(candidate)) {
						continue;
					}
					insertBatch.add(candidate);
					missingDaysSubmitted++;
					if (insertBatch.size() == AccommodationInventoryDayRepository.MAX_SEED_INSERT_ROWS) {
						inventoryRepository.seedMissingDayBatch(insertBatch);
						insertStatements++;
						insertBatch.clear();
					}
				}
			}
			if (!insertBatch.isEmpty()) {
				inventoryRepository.seedMissingDayBatch(insertBatch);
				insertStatements++;
			}
			Map<Long, Integer> verifiedCounts = inventoryRepository.countSeedDays(
				incompleteIds, range.startInclusive(), range.endExclusive());
			if (incompleteIds.stream().anyMatch(id -> verifiedCounts.getOrDefault(id, 0) != nights)) {
				throw new ReservationInventoryNotReadyException();
			}
		}
		return new SeedBatch(
			targets.size(),
			targets.getLast().getAccommodationId(),
			expectedDays,
			missingDaysSubmitted,
			insertStatements
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

	public record SeedBatch(
		int processed, long lastAccommodationId, long expectedDays,
		long missingDaysSubmitted, int insertStatements
	) {
		public SeedBatch(int processed, long lastAccommodationId) {
			this(processed, lastAccommodationId, 0, 0, 0);
		}

		public SeedBatch {
			if (processed < 0 || lastAccommodationId < 0 || expectedDays < 0
				|| missingDaysSubmitted < 0 || missingDaysSubmitted > expectedDays || insertStatements < 0) {
				throw new IllegalArgumentException("inventory seed batch values must not be negative");
			}
		}
	}
}
