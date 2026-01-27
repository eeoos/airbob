package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DateLockKeyGenerator 테스트")
class DateLockKeyGeneratorTest {

	@Nested
	@DisplayName("락 키 생성 테스트")
	class GenerateLockKeysTest {

		@Test
		@DisplayName("2박 숙박 시 체크인부터 체크아웃 전날까지의 키가 생성된다")
		void 키_생성_형식() {
			// given
			Long accommodationId = 1L;
			LocalDate checkIn = LocalDate.of(2025, 1, 26);
			LocalDate checkOut = LocalDate.of(2025, 1, 28);

			// when
			List<String> lockKeys = DateLockKeyGenerator.generateLockKeys(accommodationId, checkIn, checkOut);

			// then
			assertThat(lockKeys).hasSize(2);
			assertThat(lockKeys).containsExactly(
				"LOCK:RESERVATION:1:2025-01-26",
				"LOCK:RESERVATION:1:2025-01-27"
			);
		}

		@Test
		@DisplayName("1박 숙박 시 1개의 키만 생성된다")
		void 키_생성_1박() {
			// given
			Long accommodationId = 1L;
			LocalDate checkIn = LocalDate.of(2025, 1, 26);
			LocalDate checkOut = LocalDate.of(2025, 1, 27);

			// when
			List<String> lockKeys = DateLockKeyGenerator.generateLockKeys(accommodationId, checkIn, checkOut);

			// then
			assertThat(lockKeys).hasSize(1);
			assertThat(lockKeys).containsExactly("LOCK:RESERVATION:1:2025-01-26");
		}

		@Test
		@DisplayName("체크아웃 날짜는 락 키에 포함되지 않는다")
		void 체크아웃_날짜_미포함() {
			// given
			Long accommodationId = 1L;
			LocalDate checkIn = LocalDate.of(2025, 1, 26);
			LocalDate checkOut = LocalDate.of(2025, 1, 28);

			// when
			List<String> lockKeys = DateLockKeyGenerator.generateLockKeys(accommodationId, checkIn, checkOut);

			// then
			assertThat(lockKeys).doesNotContain("LOCK:RESERVATION:1:2025-01-28");
		}

		@Test
		@DisplayName("생성된 키는 알파벳/날짜 순으로 정렬된다")
		void 정렬_순서() {
			// given
			Long accommodationId = 1L;
			LocalDate checkIn = LocalDate.of(2025, 1, 26);
			LocalDate checkOut = LocalDate.of(2025, 1, 31);

			// when
			List<String> lockKeys = DateLockKeyGenerator.generateLockKeys(accommodationId, checkIn, checkOut);

			// then
			assertThat(lockKeys).isSorted();
			assertThat(lockKeys).hasSize(5);
			assertThat(lockKeys.get(0)).isEqualTo("LOCK:RESERVATION:1:2025-01-26");
			assertThat(lockKeys.get(4)).isEqualTo("LOCK:RESERVATION:1:2025-01-30");
		}

		@Test
		@DisplayName("다른 숙소 ID에 대해 다른 키가 생성된다")
		void 다른_숙소_ID_다른_키() {
			// given
			LocalDate checkIn = LocalDate.of(2025, 1, 26);
			LocalDate checkOut = LocalDate.of(2025, 1, 27);

			// when
			List<String> keys1 = DateLockKeyGenerator.generateLockKeys(1L, checkIn, checkOut);
			List<String> keys2 = DateLockKeyGenerator.generateLockKeys(2L, checkIn, checkOut);

			// then
			assertThat(keys1.get(0)).isEqualTo("LOCK:RESERVATION:1:2025-01-26");
			assertThat(keys2.get(0)).isEqualTo("LOCK:RESERVATION:2:2025-01-26");
			assertThat(keys1).doesNotContainAnyElementsOf(keys2);
		}

		@Test
		@DisplayName("월 경계를 넘는 예약도 올바르게 키가 생성된다")
		void 월_경계_넘는_예약() {
			// given
			Long accommodationId = 1L;
			LocalDate checkIn = LocalDate.of(2025, 1, 30);
			LocalDate checkOut = LocalDate.of(2025, 2, 2);

			// when
			List<String> lockKeys = DateLockKeyGenerator.generateLockKeys(accommodationId, checkIn, checkOut);

			// then
			assertThat(lockKeys).hasSize(3);
			assertThat(lockKeys).containsExactly(
				"LOCK:RESERVATION:1:2025-01-30",
				"LOCK:RESERVATION:1:2025-01-31",
				"LOCK:RESERVATION:1:2025-02-01"
			);
		}

		@Test
		@DisplayName("연도 경계를 넘는 예약도 올바르게 키가 생성된다")
		void 연도_경계_넘는_예약() {
			// given
			Long accommodationId = 1L;
			LocalDate checkIn = LocalDate.of(2025, 12, 31);
			LocalDate checkOut = LocalDate.of(2026, 1, 2);

			// when
			List<String> lockKeys = DateLockKeyGenerator.generateLockKeys(accommodationId, checkIn, checkOut);

			// then
			assertThat(lockKeys).hasSize(2);
			assertThat(lockKeys).containsExactly(
				"LOCK:RESERVATION:1:2025-12-31",
				"LOCK:RESERVATION:1:2026-01-01"
			);
		}

		@Test
		@DisplayName("체크인과 체크아웃이 같은 날짜면 빈 리스트가 반환된다")
		void 같은_날짜_빈_리스트() {
			// given
			Long accommodationId = 1L;
			LocalDate sameDate = LocalDate.of(2025, 1, 26);

			// when
			List<String> lockKeys = DateLockKeyGenerator.generateLockKeys(accommodationId, sameDate, sameDate);

			// then
			assertThat(lockKeys).isEmpty();
		}

		@Test
		@DisplayName("장기 숙박(30박)에 대해 올바른 개수의 키가 생성된다")
		void 장기_숙박_30박() {
			// given
			Long accommodationId = 1L;
			LocalDate checkIn = LocalDate.of(2025, 1, 1);
			LocalDate checkOut = LocalDate.of(2025, 1, 31);

			// when
			List<String> lockKeys = DateLockKeyGenerator.generateLockKeys(accommodationId, checkIn, checkOut);

			// then
			assertThat(lockKeys).hasSize(30);
			assertThat(lockKeys.get(0)).isEqualTo("LOCK:RESERVATION:1:2025-01-01");
			assertThat(lockKeys.get(29)).isEqualTo("LOCK:RESERVATION:1:2025-01-30");
		}
	}
}
