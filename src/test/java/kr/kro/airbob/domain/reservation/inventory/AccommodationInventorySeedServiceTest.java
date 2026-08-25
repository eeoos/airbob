package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccommodationInventorySeedService")
class AccommodationInventorySeedServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");
	private static final LocalDate START = LocalDate.of(2026, 8, 25);
	private static final LocalDate END_WITH_BUFFER = LocalDate.of(2026, 11, 27);

	@Mock private AccommodationRepository accommodationRepository;
	@Mock private ReservationInventoryService inventoryService;
	@Mock private AccommodationInventorySeedPolicy seedPolicy;

	private AccommodationInventorySeedService service;

	@BeforeEach
	void setUp() {
		service = new AccommodationInventorySeedService(
			accommodationRepository,
			inventoryService,
			seedPolicy,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	@DisplayName("게시 전 숙소의 현재 horizon을 같은 트랜잭션에서 전부 seed한다")
	void seedsCurrentHorizonBeforePublish() {
		Accommodation accommodation = Accommodation.builder()
			.id(7L)
			.timeZoneId("Asia/Seoul")
			.status(AccommodationStatus.DRAFT)
			.build();
		given(seedPolicy.currentRange("Asia/Seoul", NOW))
			.willReturn(new AccommodationInventorySeedPolicy.SeedRange(START, END_WITH_BUFFER));

		service.seedCurrentHorizon(accommodation);

		then(inventoryService).should().seed(7L, START, END_WITH_BUFFER);
	}

	@Test
	@DisplayName("rolling batch는 ID cursor 뒤의 게시 숙소만 제한된 크기로 seed한다")
	void seedsOneBoundedPublishedBatch() {
		AccommodationRepository.InventorySeedTarget first = target(11L, "Asia/Seoul");
		AccommodationRepository.InventorySeedTarget second = target(19L, "America/New_York");
		given(accommodationRepository.findInventorySeedTargets(
			AccommodationStatus.PUBLISHED, 10L, PageRequest.of(0, 2)))
			.willReturn(List.of(first, second));
		given(seedPolicy.currentRange("Asia/Seoul", NOW))
			.willReturn(new AccommodationInventorySeedPolicy.SeedRange(START, END_WITH_BUFFER));
		given(seedPolicy.currentRange("America/New_York", NOW))
			.willReturn(new AccommodationInventorySeedPolicy.SeedRange(
				START.minusDays(1), END_WITH_BUFFER.minusDays(1)));

		AccommodationInventorySeedService.SeedBatch batch =
			service.seedNextPublishedBatch(10L, 2);

		assertThat(batch.processed()).isEqualTo(2);
		assertThat(batch.lastAccommodationId()).isEqualTo(19L);
		then(inventoryService).should().seed(11L, START, END_WITH_BUFFER);
		then(inventoryService).should().seed(
			19L, START.minusDays(1), END_WITH_BUFFER.minusDays(1));
	}

	@Test
	@DisplayName("rolling scan has no outer transaction while each accommodation seed is transactional")
	void keepsTransactionsPerAccommodation() throws NoSuchMethodException {
		Method batchMethod = AccommodationInventorySeedService.class.getMethod(
			"seedNextPublishedBatch", long.class, int.class);
		Method accommodationSeedMethod = ReservationInventoryService.class.getMethod(
			"seed", Long.class, LocalDate.class, LocalDate.class);

		assertThat(batchMethod.getAnnotation(Transactional.class)).isNull();
		assertThat(accommodationSeedMethod.getAnnotation(Transactional.class)).isNotNull();
	}

	private AccommodationRepository.InventorySeedTarget target(Long id, String timeZoneId) {
		AccommodationRepository.InventorySeedTarget target =
			mock(AccommodationRepository.InventorySeedTarget.class);
		given(target.getAccommodationId()).willReturn(id);
		given(target.getTimeZoneId()).willReturn(timeZoneId);
		return target;
	}
}
