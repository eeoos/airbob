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
	private static final Instant SERVER_TIME = Instant.parse("2026-08-25T03:00:00Z");

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("예약 준비 응답은 checkout과 결제에 필요한 전체 계약을 노출한다")
	void readyResponseExposesCheckoutContract() throws Exception {
		Member guest = Member.builder().email("guest@test.com").nickname("guest").build();
		Accommodation accommodation = Accommodation.builder().name("discounted stay").build();
		Reservation reservation = Reservation.builder()
			.reservationUid(UUID.randomUUID())
			.accommodation(accommodation)
			.guest(guest)
			.checkInDate(LocalDate.of(2026, 9, 1))
			.checkOutDate(LocalDate.of(2026, 9, 3))
			.guestCount(2)
			.totalPrice(170_000L)
			.discountAmount(30_000L)
			.currency("KRW")
			.status(ReservationStatus.PAYMENT_PENDING)
			.expiresAt(SERVER_TIME.plusSeconds(15 * 60))
			.build();

		ReservationResponse.Ready response = ReservationResponse.Ready.from(reservation, SERVER_TIME);

		assertThat(response.status()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		assertThat(response.paymentRequired()).isTrue();
		assertThat(response.paymentAllowed()).isTrue();
		assertThat(response.holdExpiresAt()).isEqualTo(SERVER_TIME.plusSeconds(15 * 60));
		assertThat(response.serverTime()).isEqualTo(SERVER_TIME);
		assertThat(response.checkIn()).isEqualTo(LocalDate.of(2026, 9, 1));
		assertThat(response.checkOut()).isEqualTo(LocalDate.of(2026, 9, 3));
		assertThat(response.guestCount()).isEqualTo(2);
		assertThat(response.subtotal()).isEqualTo(200_000L);
		assertThat(response.discountAmount()).isEqualTo(30_000L);
		assertThat(response.amount()).isEqualTo(170_000L);
		assertThat(response.currency()).isEqualTo("KRW");

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));
		assertThat(json.path("status").asText()).isEqualTo("PAYMENT_PENDING");
		assertThat(json.path("payment_required").asBoolean()).isTrue();
		assertThat(json.path("payment_allowed").asBoolean()).isTrue();
		assertThat(json.path("hold_expires_at").asText()).isEqualTo("2026-08-25T03:15:00Z");
		assertThat(json.path("server_time").asText()).isEqualTo("2026-08-25T03:00:00Z");
		assertThat(json.path("check_in").asText()).isEqualTo("2026-09-01");
		assertThat(json.path("check_out").asText()).isEqualTo("2026-09-03");
		assertThat(json.path("guest_count").asInt()).isEqualTo(2);
		assertThat(json.path("subtotal").asLong()).isEqualTo(200_000L);
		assertThat(json.path("discount_amount").asLong()).isEqualTo(30_000L);
		assertThat(json.path("amount").asLong()).isEqualTo(170_000L);
		assertThat(json.path("currency").asText()).isEqualTo("KRW");
	}

	@Test
	@DisplayName("저장 상태가 PAYMENT_PENDING이어도 hold 만료 시각부터 EXPIRED로 응답한다")
	void readyResponseExposesEffectiveExpiryWithoutWaitingForCleanup() {
		Member guest = Member.builder().email("guest@test.com").nickname("guest").build();
		Accommodation accommodation = Accommodation.builder().name("expired stay").build();
		Reservation reservation = Reservation.builder()
			.reservationUid(UUID.randomUUID())
			.accommodation(accommodation)
			.guest(guest)
			.totalPrice(200_000L)
			.discountAmount(0L)
			.currency("KRW")
			.status(ReservationStatus.PAYMENT_PENDING)
			.expiresAt(SERVER_TIME)
			.build();

		ReservationResponse.Ready response = ReservationResponse.Ready.from(reservation, SERVER_TIME);

		assertThat(response.status()).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(response.paymentAllowed()).isFalse();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
	}

	@Test
	@DisplayName("게스트 예약 목록도 cleanup을 기다리지 않고 만료된 hold를 EXPIRED로 응답한다")
	void guestReservationInfoExposesEffectiveExpiry() {
		Reservation reservation = Reservation.builder()
			.id(20L)
			.reservationUid(UUID.randomUUID())
			.accommodation(Accommodation.builder().id(10L).name("expired stay").build())
			.status(ReservationStatus.PAYMENT_PENDING)
			.expiresAt(SERVER_TIME)
			.build();

		ReservationResponse.GuestReservationInfo response =
			ReservationResponse.GuestReservationInfo.from(reservation, SERVER_TIME);

		assertThat(response.status()).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
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
			.message("유아용 침대를 준비해 주세요")
			.expiresAt(Instant.parse("2026-03-01T00:00:00Z"))
			.createdAt(createdAt)
			.build();

		ReservationResponse.GuestDetail guestDetail = ReservationResponse.GuestDetail.from(
			reservation, null, true, SERVER_TIME);
		ReservationResponse.HostDetail hostDetail = ReservationResponse.HostDetail.from(
			reservation, null);
		ReservationResponse.GuestReservationInfo guestInfo =
			ReservationResponse.GuestReservationInfo.from(reservation, SERVER_TIME);
		ReservationResponse.HostReservationInfo hostInfo =
			ReservationResponse.HostReservationInfo.from(reservation);

		assertThat(guestDetail.createdAt()).isEqualTo(Instant.parse("2026-03-01T09:30:00Z"));
		assertThat(guestDetail.timeZoneId()).isEqualTo("America/New_York");
		assertThat(guestDetail.checkInDateTime()).isEqualTo(localCheckIn);
		assertThat(guestDetail.checkOutDateTime()).isEqualTo(localCheckOut);
		assertThat(guestDetail.checkInTime()).isEqualTo(LocalTime.of(15, 0));
		assertThat(guestDetail.checkOutTime()).isEqualTo(LocalTime.of(11, 0));
		assertThat(guestDetail.status()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(guestDetail.paymentAllowed()).isFalse();
		assertThat(guestDetail.holdExpiresAt()).isNull();
		assertThat(guestDetail.serverTime()).isEqualTo(SERVER_TIME);
		assertThat(guestDetail.requestMessage()).isEqualTo("유아용 침대를 준비해 주세요");
		assertThat(hostDetail.timeZoneId()).isEqualTo("America/New_York");
		assertThat(hostDetail.checkInDateTime()).isEqualTo(localCheckIn);
		assertThat(hostDetail.checkOutDateTime()).isEqualTo(localCheckOut);
		assertThat(hostDetail.createdAt()).isEqualTo(Instant.parse("2026-03-01T09:30:00Z"));
		assertThat(hostDetail.requestMessage()).isEqualTo("유아용 침대를 준비해 주세요");
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
