package kr.kro.airbob.domain.reservation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

@JsonTest
@DisplayName("예약 응답 시간대 테스트")
	class ReservationResponseTest {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("예약 준비 응답은 결제 필요 여부와 현재 상태를 명시한다")
	void readyResponseExposesPaymentRequirement() throws Exception {
		Member guest = Member.builder().email("guest@test.com").nickname("guest").build();
		Accommodation accommodation = Accommodation.builder().name("free stay").build();
		Reservation reservation = Reservation.builder()
			.reservationUid(UUID.randomUUID())
			.accommodation(accommodation)
			.guest(guest)
			.totalPrice(0L)
			.status(ReservationStatus.CONFIRMED)
			.build();

		ReservationResponse.Ready response = ReservationResponse.Ready.from(reservation);

		assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(response.paymentRequired()).isFalse();

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));
		assertThat(json.path("status").asText()).isEqualTo("CONFIRMED");
		assertThat(json.path("payment_required").asBoolean()).isFalse();
		assertThat(json.path("amount").asLong()).isZero();
	}

	@Test
	@DisplayName("숙소 시간대가 바뀌어도 예약 당시 시간대 스냅샷으로 현지 시각을 복원한다")
	void detailsUseReservationTimeZoneSnapshot() {
		ZoneId reservationZone = ZoneId.of("America/New_York");
		LocalDateTime createdAt = LocalDateTime.of(2026, 3, 1, 9, 30);
		LocalDateTime localCheckIn = LocalDateTime.of(2026, 3, 8, 15, 0);
		LocalDateTime localCheckOut = LocalDateTime.of(2026, 3, 10, 11, 0);
		Member host = Member.builder().id(1L).nickname("host").build();
		Member guest = Member.builder().id(2L).nickname("guest").build();
		Accommodation accommodation = Accommodation.builder()
			.id(10L)
			.member(host)
			.name("time-zone-snapshot")
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId("America/Los_Angeles")
			.status(AccommodationStatus.PUBLISHED)
			.build();
		Reservation reservation = Reservation.builder()
			.id(20L)
			.reservationUid(UUID.randomUUID())
			.reservationCode("ABC1234567")
			.accommodation(accommodation)
			.guest(guest)
			.checkInDate(LocalDate.of(2026, 3, 8))
			.checkOutDate(LocalDate.of(2026, 3, 10))
			.checkInAt(localCheckIn.atZone(reservationZone).toInstant())
			.checkOutAt(localCheckOut.atZone(reservationZone).toInstant())
			.timeZoneId(reservationZone.getId())
			.guestCount(2)
			.totalPrice(200_000L)
			.currency("KRW")
			.status(ReservationStatus.CONFIRMED)
			.expiresAt(Instant.parse("2026-03-01T00:00:00Z"))
			.createdAt(createdAt)
			.build();

		ReservationResponse.GuestDetail guestDetail = ReservationResponse.GuestDetail.from(
			reservation, null, true);
		ReservationResponse.HostDetail hostDetail = ReservationResponse.HostDetail.from(
			reservation, null);
		ReservationResponse.GuestReservationInfo guestInfo =
			ReservationResponse.GuestReservationInfo.from(reservation);
		ReservationResponse.HostReservationInfo hostInfo =
			ReservationResponse.HostReservationInfo.from(reservation);

		assertThat(guestDetail.createdAt()).isEqualTo(Instant.parse("2026-03-01T09:30:00Z"));
		assertThat(guestDetail.timeZoneId()).isEqualTo("America/New_York");
		assertThat(guestDetail.checkInDateTime()).isEqualTo(localCheckIn);
		assertThat(guestDetail.checkOutDateTime()).isEqualTo(localCheckOut);
		assertThat(guestDetail.checkInTime()).isEqualTo(LocalTime.of(15, 0));
		assertThat(guestDetail.checkOutTime()).isEqualTo(LocalTime.of(11, 0));
		assertThat(hostDetail.timeZoneId()).isEqualTo("America/New_York");
		assertThat(hostDetail.checkInDateTime()).isEqualTo(localCheckIn);
		assertThat(hostDetail.checkOutDateTime()).isEqualTo(localCheckOut);
		assertThat(hostDetail.createdAt()).isEqualTo(Instant.parse("2026-03-01T09:30:00Z"));
		assertThat(guestInfo.createdAt()).isEqualTo(Instant.parse("2026-03-01T09:30:00Z"));
		assertThat(guestInfo.status()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(hostInfo.createdAt()).isEqualTo(Instant.parse("2026-03-01T09:30:00Z"));
	}

	@Test
	@DisplayName("예약 API의 절대 시각은 Z를 포함하고 숙박 현지 시각은 time_zone_id와 함께 응답한다")
	void serializesUtcAndLocalStayContract() throws Exception {
		ReservationResponse.GuestDetail response = ReservationResponse.GuestDetail.builder()
			.reservationUid("reservation-uid")
			.status(ReservationStatus.CANCELLATION_PENDING)
			.createdAt(Instant.parse("2026-08-12T05:30:00.123456Z"))
			.checkInDateTime(LocalDateTime.of(2026, 8, 20, 15, 0))
			.checkOutDateTime(LocalDateTime.of(2026, 8, 22, 11, 0))
			.timeZoneId("America/New_York")
			.build();
		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

		assertThat(json.path("created_at").asText())
			.isEqualTo("2026-08-12T05:30:00.123456Z");
		assertThat(json.path("check_in_date_time").asText())
			.isEqualTo("2026-08-20T15:00:00");
		assertThat(json.path("time_zone_id").asText()).isEqualTo("America/New_York");
		assertThat(json.path("status").asText()).isEqualTo("CANCELLATION_PENDING");
	}
}
