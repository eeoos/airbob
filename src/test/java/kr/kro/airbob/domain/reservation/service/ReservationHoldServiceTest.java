package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationHoldService 테스트")
class ReservationHoldServiceTest {

	@InjectMocks
	private ReservationHoldService holdService;

	@Mock
	private RedisTemplate<String, String> redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	@Captor
	private ArgumentCaptor<List<String>> listCaptor;

	private Long accommodationId;
	private LocalDate checkIn;
	private LocalDate checkOut;

	@BeforeEach
	void setUp() {
		accommodationId = 1L;
		checkIn = LocalDate.of(2025, 1, 26);
		checkOut = LocalDate.of(2025, 1, 28);

		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
	}

	@Nested
	@DisplayName("날짜 Hold 설정 테스트")
	class HoldDatesTest {

		@Test
		@DisplayName("Hold 설정 시 executePipelined으로 SET EX가 실행된다")
		void Hold_설정_성공() {
			// given
			given(redisTemplate.executePipelined(any(RedisCallback.class)))
				.willReturn(Collections.emptyList());

			// when
			holdService.holdDates(accommodationId, checkIn, checkOut);

			// then
			then(redisTemplate).should().executePipelined(any(RedisCallback.class));
		}

		@Test
		@DisplayName("1박 예약 시에도 pipeline이 정상 실행된다")
		void Hold_1박() {
			// given
			LocalDate oneNightCheckOut = LocalDate.of(2025, 1, 27);
			given(redisTemplate.executePipelined(any(RedisCallback.class)))
				.willReturn(Collections.emptyList());

			// when
			holdService.holdDates(accommodationId, checkIn, oneNightCheckOut);

			// then
			then(redisTemplate).should().executePipelined(any(RedisCallback.class));
		}
	}

	@Nested
	@DisplayName("날짜 Hold 확인 테스트")
	class IsAnyDateHeldTest {

		@Test
		@DisplayName("Hold된 날짜가 있으면 true를 반환한다")
		void Hold_확인_존재() {
			// given
			given(valueOperations.multiGet(anyList()))
				.willReturn(Arrays.asList("held", null));

			// when
			boolean result = holdService.isAnyDateHeld(accommodationId, checkIn, checkOut);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("Hold된 날짜가 없으면 false를 반환한다")
		void Hold_확인_미존재() {
			// given
			given(valueOperations.multiGet(anyList()))
				.willReturn(Arrays.asList(null, null));

			// when
			boolean result = holdService.isAnyDateHeld(accommodationId, checkIn, checkOut);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("모든 날짜가 Hold되어 있으면 true를 반환한다")
		void Hold_확인_모두_존재() {
			// given
			given(valueOperations.multiGet(anyList()))
				.willReturn(Arrays.asList("held", "held"));

			// when
			boolean result = holdService.isAnyDateHeld(accommodationId, checkIn, checkOut);

			// then
			assertThat(result).isTrue();
		}

		@Test
		@DisplayName("MGET 결과가 null이면 false를 반환한다")
		void Hold_확인_null_결과() {
			// given
			given(valueOperations.multiGet(anyList()))
				.willReturn(null);

			// when
			boolean result = holdService.isAnyDateHeld(accommodationId, checkIn, checkOut);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("빈 리스트 결과면 false를 반환한다")
		void Hold_확인_빈_리스트() {
			// given
			given(valueOperations.multiGet(anyList()))
				.willReturn(Collections.emptyList());

			// when
			boolean result = holdService.isAnyDateHeld(accommodationId, checkIn, checkOut);

			// then
			assertThat(result).isFalse();
		}

		@Test
		@DisplayName("올바른 키 형식(HOLD:RESERVATION:{id}:{date})으로 MGET이 호출된다")
		void Hold_확인_키_검증() {
			// given
			given(valueOperations.multiGet(anyList()))
				.willReturn(Arrays.asList(null, null));

			// when
			holdService.isAnyDateHeld(accommodationId, checkIn, checkOut);

			// then
			then(valueOperations).should().multiGet(listCaptor.capture());
			List<String> queriedKeys = listCaptor.getValue();

			assertThat(queriedKeys).hasSize(2);
			assertThat(queriedKeys).contains(
				"HOLD:RESERVATION:1:2025-01-26",
				"HOLD:RESERVATION:1:2025-01-27"
			);
		}
	}

	@Nested
	@DisplayName("Hold 제거 테스트")
	class RemoveHoldTest {

		@Test
		@DisplayName("Hold 제거 시 DELETE로 Redis 키가 삭제된다")
		void Hold_제거() {
			// when
			holdService.removeHold(accommodationId, checkIn, checkOut);

			// then
			then(redisTemplate).should().delete(listCaptor.capture());
			List<String> deletedKeys = listCaptor.getValue();

			assertThat(deletedKeys).hasSize(2);
			assertThat(deletedKeys).contains(
				"HOLD:RESERVATION:1:2025-01-26",
				"HOLD:RESERVATION:1:2025-01-27"
			);
		}

		@Test
		@DisplayName("Hold 제거 시 Redis 장애가 발생해도 예외가 전파되지 않는다")
		void Hold_제거_실패_시_예외_미전파() {
			// given
			given(redisTemplate.delete(anyList()))
				.willThrow(new DataAccessException("Redis connection failed") {});

			// when & then
			assertThatCode(() -> holdService.removeHold(accommodationId, checkIn, checkOut))
				.doesNotThrowAnyException();
		}
	}
}
