package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Accommodation inventory retention service")
class AccommodationInventoryRetentionServiceTest {

	@Mock private AccommodationInventoryDayRepository repository;

	@Test
	@DisplayName("delegates one bounded FREE-only repository delete per transaction")
	void deletesOneBatch() {
		LocalDate cutoff = LocalDate.of(2026, 7, 26);
		given(repository.deletePastFreeDays(cutoff, 1000)).willReturn(73);
		AccommodationInventoryRetentionService service =
			new AccommodationInventoryRetentionService(repository);

		assertThat(service.deleteNextPastFreeBatch(cutoff, 1000)).isEqualTo(73);
		then(repository).should().deletePastFreeDays(cutoff, 1000);
	}

	@Test
	@DisplayName("rejects an unbounded or empty delete batch")
	void rejectsInvalidBatchSize() {
		AccommodationInventoryRetentionService service =
			new AccommodationInventoryRetentionService(repository);

		assertThatThrownBy(() -> service.deleteNextPastFreeBatch(LocalDate.now(), 0))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
