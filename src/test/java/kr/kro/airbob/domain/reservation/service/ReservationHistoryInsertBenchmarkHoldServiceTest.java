package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationHistory INSERT 벤치마크 hold 격리 테스트")
class ReservationHistoryInsertBenchmarkHoldServiceTest {

	@Mock private RedisTemplate<String, String> redisTemplate;

	private ReservationHistoryInsertBenchmarkHoldService holdService;

	@BeforeEach
	void setUp() {
		holdService = new ReservationHistoryInsertBenchmarkHoldService(redisTemplate);
	}

	@Test
	@DisplayName("측정 중 removeHold는 Redis를 호출하지 않고 정확한 인자를 기록한다")
	void recordsHoldRemovalWithoutRedisNetworkIo() {
		LocalDate checkIn = LocalDate.of(2030, 1, 1);
		LocalDate checkOut = LocalDate.of(2030, 1, 3);
		holdService.startRecording();

		holdService.removeHold(11L, checkIn, checkOut);
		var snapshot = holdService.finishRecording();

		assertThat(snapshot.removals()).containsExactly(
			new ReservationHistoryInsertBenchmarkHoldService.HoldRemoval(11L, checkIn, checkOut)
		);
		then(redisTemplate).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("측정 context 밖에서는 운영 ReservationHoldService 동작을 그대로 위임한다")
	void delegatesOutsideBenchmarkRecording() {
		LocalDate checkIn = LocalDate.of(2030, 1, 1);
		LocalDate checkOut = LocalDate.of(2030, 1, 3);

		holdService.removeHold(11L, checkIn, checkOut);

		then(redisTemplate).should().delete(List.of(
			"HOLD:RESERVATION:11:2030-01-01",
			"HOLD:RESERVATION:11:2030-01-02"
		));
	}

	@Test
	@DisplayName("중첩 recording은 기존 context를 덮어쓰지 않는다")
	void rejectsNestedRecording() {
		holdService.startRecording();

		assertThatIllegalStateException().isThrownBy(holdService::startRecording);

		holdService.clearRecording();
	}
}
