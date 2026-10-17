package kr.kro.airbob.domain.reservation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationQuote;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.repository.projection.GuestReservationDetailProjection;
import kr.kro.airbob.domain.reservation.repository.projection.GuestReservationListProjection;
import kr.kro.airbob.domain.reservation.repository.projection.HostReservationDetailProjection;
import kr.kro.airbob.domain.reservation.repository.projection.HostReservationListProjection;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationResponse {

	@Builder
	@Schema(name = "ReservationResponseQuote")
	public record Quote(
		@Schema(description = "체크아웃에 전달할 견적 UID", example = "00000000-0000-0000-0000-000000000010", requiredMode = Schema.RequiredMode.REQUIRED)
		UUID quoteUid,
		@Schema(example = "101", requiredMode = Schema.RequiredMode.REQUIRED)
		Long accommodationId,
		@Schema(description = "견적 시점의 숙소 이름", example = "도심 속 작은 숙소", requiredMode = Schema.RequiredMode.REQUIRED)
		String orderName,
		@Schema(description = "숙소 현지 체크인 날짜", example = "2027-01-10", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate checkIn,
		@Schema(description = "숙소 현지 체크아웃 날짜. 해당 날짜의 밤은 제외합니다.", example = "2027-01-12", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate checkOut,
		@Schema(example = "2", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		Integer guestCount,
		@Schema(description = "견적 시점의 1박 요금", example = "100000", requiredMode = Schema.RequiredMode.REQUIRED)
		Long nightlyPrice,
		@Schema(description = "숙박 일수", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
		Long nights,
		@Schema(description = "할인 전 총 숙박 요금", example = "200000", requiredMode = Schema.RequiredMode.REQUIRED)
		Long subtotal,
		@Schema(description = "적용한 할인액", example = "10000", requiredMode = Schema.RequiredMode.REQUIRED)
		Long discountAmount,
		@Schema(description = "할인 후 결제 금액. 서버 값을 그대로 사용합니다.", example = "190000", requiredMode = Schema.RequiredMode.REQUIRED)
		Long amount,
		@Schema(description = "요금 통화", example = "KRW", requiredMode = Schema.RequiredMode.REQUIRED)
		String currency,
		@Schema(description = "금액이 0보다 큰지 여부. false이면 결제 시도 발급·결제 확인을 건너뜁니다.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean paymentRequired,
		@Schema(description = "견적은 재고를 확보하지 않으므로 항상 false입니다.", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean inventoryHeld,
		@Schema(description = "서버의 현재 UTC 시각. 남은 시간 판단 기준입니다.", example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant serverTime
	) {
		public static Quote from(ReservationQuote quote, Instant serverTime) {
			return Quote.builder()
				.quoteUid(quote.getQuoteUid())
				.accommodationId(quote.getAccommodationId())
				.orderName(quote.getOrderName())
				.checkIn(quote.getCheckInDate())
				.checkOut(quote.getCheckOutDate())
				.guestCount(quote.getGuestCount())
				.nightlyPrice(quote.getNightlyPrice())
				.nights(quote.getNights())
				.subtotal(quote.getSubtotal())
				.discountAmount(quote.getDiscountAmount())
				.amount(quote.getAmount())
				.currency(quote.getCurrency())
				.paymentRequired(quote.getAmount() > 0)
				.inventoryHeld(false)
				.serverTime(serverTime)
				.build();
		}
	}

	@Builder
	@Schema(name = "ReservationResponseReady")
	public record Ready(
		@Schema(description = "예약 UID. 예약 상세·보유·취소 경로와 결제 주문 ID에 사용합니다.", example = "00000000-0000-0000-0000-000000000020", requiredMode = Schema.RequiredMode.REQUIRED)
		String reservationUid,
		@Schema(description = "숙소 이름. 체크아웃 재조회에서는 현재 숙소 이름입니다.", example = "도심 속 작은 숙소", requiredMode = Schema.RequiredMode.REQUIRED)
		String orderName,
		@Schema(description = "숙소 현지 체크인 날짜", example = "2027-01-10", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate checkIn,
		@Schema(description = "숙소 현지 체크아웃 날짜. 해당 날짜의 밤은 제외합니다.", example = "2027-01-12", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate checkOut,
		@Schema(example = "2", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		Integer guestCount,
		@Schema(description = "할인 전 총 숙박 요금", example = "200000", requiredMode = Schema.RequiredMode.REQUIRED)
		Long subtotal,
		@Schema(description = "적용한 할인액", example = "10000", requiredMode = Schema.RequiredMode.REQUIRED)
		Long discountAmount,
		@Schema(description = "할인 후 결제 금액. 서버 값을 그대로 사용합니다.", example = "190000", requiredMode = Schema.RequiredMode.REQUIRED)
		Long amount,
		@Schema(description = "요금 통화", example = "KRW", requiredMode = Schema.RequiredMode.REQUIRED)
		String currency,
		@Schema(description = "조회 시점의 상태. 만료된 결제 대기는 정리 전에도 EXPIRED입니다.", example = "PAYMENT_PENDING", requiredMode = Schema.RequiredMode.REQUIRED)
		ReservationStatus status,
		@Schema(description = "금액이 0보다 큰지 여부. false이면 결제 시도 발급·결제 확인을 건너뜁니다.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean paymentRequired,
		@Schema(description = "유료 결제 대기이며 보유 만료 전인지 여부. 최초 시도 발급의 최소 잔여 시간은 별도로 검사합니다.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean paymentAllowed,
		@Schema(description = "결제 대기 상태의 UTC 보유 기한. 정리 전에는 이미 지난 시각도 반환되며 다른 저장 상태에서는 null입니다.", example = "2027-01-01T00:15:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Instant holdExpiresAt,
		@Schema(description = "서버의 현재 UTC 시각. 남은 시간 판단 기준입니다.", example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant serverTime,
		@Schema(description = "예약 게스트의 현재 이메일. 과거 데이터는 null일 수 있습니다.", example = "docs@example.invalid", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String customerEmail,
		@Schema(description = "예약 게스트의 현재 닉네임. 과거 데이터는 null일 수 있습니다.", example = "여행자", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String customerName
	) {
		public static Ready from(Reservation reservation, Instant serverTime) {
			long discountAmount = reservation.getDiscountAmount() == null
				? 0L : reservation.getDiscountAmount();
			return Ready.builder()
				.reservationUid(reservation.getReservationUid().toString())
				.orderName(reservation.getAccommodation().getName())
				.checkIn(reservation.getCheckInDate())
				.checkOut(reservation.getCheckOutDate())
				.guestCount(reservation.getGuestCount())
				.subtotal(Math.addExact(reservation.getTotalPrice(), discountAmount))
				.discountAmount(discountAmount)
				.amount(reservation.getTotalPrice())
				.currency(reservation.getCurrency())
				.status(reservation.effectiveStatus(serverTime))
				.paymentRequired(reservation.requiresPayment())
				.paymentAllowed(reservation.isPaymentAllowedAt(serverTime))
				.holdExpiresAt(activeHoldExpiresAt(reservation))
				.serverTime(serverTime)
				.customerEmail(reservation.getGuest().getEmail())
				.customerName(reservation.getGuest().getNickname())
				.build();
		}
	}

	@Schema(name = "ReservationResponseHoldRelease")
	public record HoldRelease(
		@Schema(description = "예약 UID. 예약 상세·보유·취소 경로와 결제 주문 ID에 사용합니다.", example = "00000000-0000-0000-0000-000000000020", requiredMode = Schema.RequiredMode.REQUIRED)
		String reservationUid,
		@Schema(description = "해제 후 상태. 성공 응답은 EXPIRED입니다.", example = "EXPIRED", requiredMode = Schema.RequiredMode.REQUIRED)
		ReservationStatus status,
		@Schema(description = "이번 요청에서 저장된 결제 대기 상태를 EXPIRED로 변경했는지 여부. 이미 EXPIRED였다면 false입니다.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean releasedNow,
		@Schema(description = "서버의 현재 UTC 시각. 남은 시간 판단 기준입니다.", example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant serverTime
	) {
		public static HoldRelease from(Reservation reservation, boolean releasedNow, Instant serverTime) {
			return new HoldRelease(
				reservation.getReservationUid().toString(),
				reservation.getStatus(),
				releasedNow,
				serverTime
			);
		}
	}

	@Schema(name = "ReservationResponsePaymentAttemptReady")
	public record PaymentAttemptReady(
		@Schema(description = "결제 확인 요청에 전달할 시도 ID. 유효한 재호출에는 같은 값을 반환합니다.", example = "00000000-0000-0000-0000-000000000030", requiredMode = Schema.RequiredMode.REQUIRED)
		UUID paymentAttemptId,
		@Schema(description = "결제 주문 ID. reservation_uid와 같습니다.", example = "00000000-0000-0000-0000-000000000020", requiredMode = Schema.RequiredMode.REQUIRED)
		String orderId,
		@Schema(description = "할인 후 결제 금액. 서버 값을 그대로 사용합니다.", example = "190000", requiredMode = Schema.RequiredMode.REQUIRED)
		long amount,
		@Schema(description = "요금 통화", example = "KRW", requiredMode = Schema.RequiredMode.REQUIRED)
		String currency,
		@Schema(description = "발급 시 유효한 UTC 보유 기한. 토큰 발급으로 연장되지 않습니다.", example = "2027-01-01T00:15:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant holdExpiresAt,
		@Schema(description = "응답 시점의 남은 보유 시간(초). 소수점 이하는 버립니다.", example = "900", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		long remainingSeconds,
		@Schema(description = "서버의 현재 UTC 시각. 남은 시간 판단 기준입니다.", example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant serverTime
	) {
		public static PaymentAttemptReady from(
			Reservation reservation,
			long remainingSeconds,
			Instant serverTime
		) {
			return new PaymentAttemptReady(
				reservation.getPaymentAttemptUid(),
				reservation.getReservationUid().toString(),
				reservation.getTotalPrice(),
				reservation.getCurrency(),
				reservation.getExpiresAt(),
				remainingSeconds,
				serverTime
			);
		}
	}

	@Builder
	@Schema(name = "ReservationResponseGuestReservationInfo")
	public record GuestReservationInfo(
		@Schema(description = "예약의 숫자 ID. 상세·취소 API에는 reservation_uid를 사용합니다.", example = "201", requiredMode = Schema.RequiredMode.REQUIRED)
		long reservationId,
		@Schema(description = "예약 UID. 예약 상세·보유·취소 경로와 결제 주문 ID에 사용합니다.", example = "00000000-0000-0000-0000-000000000020", requiredMode = Schema.RequiredMode.REQUIRED)
		String reservationUid,
		@Schema(description = "숙소 현지 체크인 날짜", example = "2027-01-10", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate checkInDate,
		@Schema(description = "숙소 현지 체크아웃 날짜", example = "2027-01-12", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate checkOutDate,
		@Schema(description = "예약에 저장한 IANA 시간대", example = "Asia/Seoul", requiredMode = Schema.RequiredMode.REQUIRED)
		String timeZoneId,
		@Schema(description = "조회 시점의 상태. 만료된 결제 대기는 정리 전에도 EXPIRED입니다.", example = "PAYMENT_PENDING", requiredMode = Schema.RequiredMode.REQUIRED)
		ReservationStatus status,
		@Schema(description = "예약 생성 UTC 시각. 과거 데이터는 null일 수 있습니다.", example = "2027-01-01T00:00:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Instant createdAt,
		@Schema(description = "숙소의 현재 기본 정보", requiredMode = Schema.RequiredMode.REQUIRED)
		AccommodationResponse.AccommodationBasicInfo accommodation
	) {
		public static GuestReservationInfo from(GuestReservationListProjection reservation, Instant serverTime) {

			return GuestReservationInfo.builder()
				.reservationId(reservation.id())
				.reservationUid(reservation.reservationUid().toString())
				.checkInDate(reservation.checkInDate())
				.checkOutDate(reservation.checkOutDate())
				.timeZoneId(reservation.timeZoneId())
				.status(Reservation.effectiveStatus(reservation.status(), reservation.expiresAt(), serverTime))
				.createdAt(toUtcInstant(reservation.createdAt()))
				.accommodation(new AccommodationResponse.AccommodationBasicInfo(
					reservation.accommodationId(), reservation.accommodationName(), reservation.accommodationThumbnailUrl()))
				.build();
		}
	}

	@Builder
	@Schema(name = "ReservationResponseGuestReservationInfos")
	public record GuestReservationInfos(
		@Schema(description = "이번 페이지의 예약 목록. 결과가 없으면 빈 배열입니다.", requiredMode = Schema.RequiredMode.REQUIRED)
		List<GuestReservationInfo> reservations,
		@Schema(description = "다음 페이지 조회 정보", requiredMode = Schema.RequiredMode.REQUIRED)
		CursorResponse.PageInfo pageInfo
	) {
		public static GuestReservationInfos from(
			List<GuestReservationInfo> reservationInfos,
			CursorResponse.PageInfo pageInfo) {
			return GuestReservationInfos.builder()
				.reservations(reservationInfos)
				.pageInfo(pageInfo)
				.build();
		}
	}

	@Builder
	@Schema(name = "ReservationResponseGuestDetail", example = """
		{
		  "reservation_uid": "00000000-0000-0000-0000-000000000020",
		  "reservation_code": "AB12CD",
		  "status": "PAYMENT_PENDING",
		  "payment_allowed": true,
		  "hold_expires_at": "2027-01-01T00:15:00Z",
		  "server_time": "2027-01-01T00:00:00Z",
		  "created_at": "2027-01-01T00:00:00Z",
		  "guest_count": 2,
		  "check_in_date_time": "2027-01-10T15:00:00",
		  "check_out_date_time": "2027-01-12T11:00:00",
		  "time_zone_id": "Asia/Seoul",
		  "check_in_time": "15:00:00",
		  "check_out_time": "11:00:00",
		  "request_message": "늦은 체크인 예정입니다.",
		  "can_write_review": false,
		  "accommodation": {
		    "id": 101,
		    "name": "도심 속 작은 숙소",
		    "thumbnail_url": null
		  },
		  "address": {
		    "country": "대한민국",
		    "state": "서울특별시",
		    "city": "서울",
		    "district": "종로구",
		    "street": "가상로 123",
		    "detail": "101호",
		    "postal_code": "03000"
		  },
		  "coordinate": {
		    "latitude": 37.57,
		    "longitude": 126.98
		  },
		  "host": {
		    "id": 10,
		    "nickname": "호스트",
		    "thumbnail_image_url": null
		  },
		  "payment": null
		}
		""")
	public record GuestDetail(
		@Schema(description = "예약 UID. 예약 상세·보유·취소 경로와 결제 주문 ID에 사용합니다.", example = "00000000-0000-0000-0000-000000000020", requiredMode = Schema.RequiredMode.REQUIRED)
		String reservationUid,
		@Schema(description = "화면에 표시하는 예약 코드. API 경로에는 reservation_uid를 사용합니다.", example = "AB12CD", requiredMode = Schema.RequiredMode.REQUIRED)
		String reservationCode,
		@Schema(description = "조회 시점의 상태. 만료된 결제 대기는 정리 전에도 EXPIRED입니다.", example = "PAYMENT_PENDING", requiredMode = Schema.RequiredMode.REQUIRED)
		ReservationStatus status,
		@Schema(description = "유료 결제 대기이며 보유 만료 전인지 여부. 최초 시도 발급의 최소 잔여 시간은 별도로 검사합니다.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean paymentAllowed,
		@Schema(description = "결제 대기 상태의 UTC 보유 기한. 정리 전에는 이미 지난 시각도 반환되며 다른 저장 상태에서는 null입니다.", example = "2027-01-01T00:15:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Instant holdExpiresAt,
		@Schema(description = "서버의 현재 UTC 시각. 남은 시간 판단 기준입니다.", example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant serverTime,
		@Schema(description = "예약 생성 UTC 시각. 과거 데이터는 null일 수 있습니다.", example = "2027-01-01T00:00:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Instant createdAt,
		@Schema(example = "2", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		Integer guestCount,
		@Schema(type = "string", format = "local-date-time", description = "예약에 저장한 숙소 현지 체크인 일시. time_zone_id와 함께 읽습니다.", example = "2027-01-10T15:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDateTime checkInDateTime,
		@Schema(type = "string", format = "local-date-time", description = "예약에 저장한 숙소 현지 체크아웃 일시. time_zone_id와 함께 읽습니다.", example = "2027-01-12T11:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDateTime checkOutDateTime,
		@Schema(description = "예약에 저장한 IANA 시간대", example = "Asia/Seoul", requiredMode = Schema.RequiredMode.REQUIRED)
		String timeZoneId,
		@Schema(type = "string", description = "숙소 현지 체크인 시간", example = "15:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalTime checkInTime,
		@Schema(type = "string", description = "숙소 현지 체크아웃 시간", example = "11:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalTime checkOutTime,
		@Schema(description = "체크아웃 시 게스트가 보낸 요청 사항", example = "늦은 체크인 예정입니다.", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String requestMessage,
		@Schema(description = "현재 회원이 이 숙소에 리뷰를 작성할 수 있는지 여부. API 설명의 게시·상태·체크아웃·기존 리뷰 조건을 따릅니다.", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
		Boolean canWriteReview,
		@Schema(description = "숙소의 현재 기본 정보", requiredMode = Schema.RequiredMode.REQUIRED)
		AccommodationResponse.AccommodationBasicInfo accommodation,
		@Schema(description = "숙소의 현재 주소", requiredMode = Schema.RequiredMode.REQUIRED)
		AddressResponse.AddressInfo address,
		@Schema(description = "숙소의 현재 좌표", requiredMode = Schema.RequiredMode.REQUIRED)
		AddressResponse.Coordinate coordinate,
		@Schema(description = "숙소 현재 소유자의 회원 정보", requiredMode = Schema.RequiredMode.REQUIRED)
		MemberResponse.MemberInfo host,
		@Schema(description = "결제 기록. 미결제·0원 예약 등 기록이 없으면 null입니다.", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		PaymentResponse.GuestPaymentInfo payment
	) {
		public static GuestDetail from(GuestReservationDetailProjection detail,
			boolean canWriteReview,
			Instant serverTime) {
			ZoneId timeZone = ZoneId.of(detail.timeZoneId());
			LocalDateTime checkInDateTime = LocalDateTime.ofInstant(detail.checkInAt(), timeZone);
			LocalDateTime checkOutDateTime = LocalDateTime.ofInstant(detail.checkOutAt(), timeZone);

			return GuestDetail.builder()
				.reservationUid(detail.reservationUid().toString())
				.reservationCode(detail.reservationCode())
				.status(Reservation.effectiveStatus(detail.status(), detail.expiresAt(), serverTime))
				.paymentAllowed(Reservation.isPaymentAllowedAt(
					detail.status(), detail.totalPrice(), detail.expiresAt(), serverTime))
				.holdExpiresAt(detail.status() == ReservationStatus.PAYMENT_PENDING ? detail.expiresAt() : null)
				.serverTime(serverTime)
				.createdAt(toUtcInstant(detail.createdAt()))
				.guestCount(detail.guestCount())
				.checkInDateTime(checkInDateTime)
				.checkOutDateTime(checkOutDateTime)
				.timeZoneId(detail.timeZoneId())
				.checkInTime(checkInDateTime.toLocalTime())
				.checkOutTime(checkOutDateTime.toLocalTime())
				.requestMessage(detail.requestMessage())
				.canWriteReview(canWriteReview)
				.accommodation(new AccommodationResponse.AccommodationBasicInfo(
					detail.accommodationId(), detail.accommodationName(), detail.accommodationThumbnailUrl()))
				.address(new AddressResponse.AddressInfo(detail.country(), detail.state(), detail.city(),
					detail.district(), detail.street(), detail.addressDetail(), detail.postalCode()))
				.coordinate(new AddressResponse.Coordinate(detail.latitude(), detail.longitude()))
				.host(new MemberResponse.MemberInfo(detail.hostId(), detail.hostNickname(), detail.hostThumbnailImageUrl()))
				.payment(detail.paymentId() == null ? null : new PaymentResponse.GuestPaymentInfo(
					detail.paymentMethod().getDescription(), detail.paymentAmount(),
					detail.paymentStatus(), detail.paymentApprovedAt()))
				.build();
		}
	}

	private static Instant activeHoldExpiresAt(Reservation reservation) {
		return reservation.getStatus() == ReservationStatus.PAYMENT_PENDING
			? reservation.getExpiresAt()
			: null;
	}


	@Builder
	@Schema(name = "ReservationResponseHostReservationInfo")
	public record HostReservationInfo(
		@Schema(description = "예약 UID. 예약 상세·보유·취소 경로와 결제 주문 ID에 사용합니다.", example = "00000000-0000-0000-0000-000000000020", requiredMode = Schema.RequiredMode.REQUIRED)
		String reservationUid,
		@Schema(description = "화면에 표시하는 예약 코드. API 경로에는 reservation_uid를 사용합니다.", example = "AB12CD", requiredMode = Schema.RequiredMode.REQUIRED)
		String reservationCode,
		@Schema(description = "할인 후 예약 금액", example = "190000", requiredMode = Schema.RequiredMode.REQUIRED)
		Long totalPrice,
		@Schema(description = "요금 통화", example = "KRW", requiredMode = Schema.RequiredMode.REQUIRED)
		String currency,
		@Schema(example = "2", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		int guestCount,
		@Schema(description = "숙소 현지 체크인 날짜", example = "2027-01-10", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate checkInDate,
		@Schema(description = "숙소 현지 체크아웃 날짜", example = "2027-01-12", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDate checkOutDate,
		@Schema(description = "예약에 저장한 IANA 시간대", example = "Asia/Seoul", requiredMode = Schema.RequiredMode.REQUIRED)
		String timeZoneId,
		@Schema(description = "DB에 저장된 예약 상태", example = "CONFIRMED", requiredMode = Schema.RequiredMode.REQUIRED)
		ReservationStatus status,
		@Schema(description = "예약 생성 UTC 시각. 과거 데이터는 null일 수 있습니다.", example = "2027-01-01T00:00:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Instant createdAt,
		@Schema(description = "게스트의 현재 회원 정보", requiredMode = Schema.RequiredMode.REQUIRED)
		MemberResponse.MemberInfo guest,
		@Schema(description = "숙소의 현재 기본 정보", requiredMode = Schema.RequiredMode.REQUIRED)
		AccommodationResponse.AccommodationBasicInfo accommodation
	) {
		public static HostReservationInfo from(HostReservationListProjection reservation) {
			return HostReservationInfo.builder()
				.reservationUid(reservation.reservationUid().toString())
				.reservationCode(reservation.reservationCode())
				.totalPrice(reservation.totalPrice())
				.currency(reservation.currency())
				.guestCount(reservation.guestCount())
				.checkInDate(reservation.checkInDate())
				.checkOutDate(reservation.checkOutDate())
				.timeZoneId(reservation.timeZoneId())
				.status(reservation.status())
				.createdAt(toUtcInstant(reservation.createdAt()))
				.guest(new MemberResponse.MemberInfo(
					reservation.guestId(), reservation.guestNickname(), reservation.guestThumbnailImageUrl()))
				.accommodation(new AccommodationResponse.AccommodationBasicInfo(
					reservation.accommodationId(), reservation.accommodationName(), reservation.accommodationThumbnailUrl()))
				.build();
		}
	}

	@Builder
	@Schema(name = "ReservationResponseHostReservationInfos")
	public record HostReservationInfos(
		@Schema(description = "이번 페이지의 예약 목록. 결과가 없으면 빈 배열입니다.", requiredMode = Schema.RequiredMode.REQUIRED)
		List<HostReservationInfo> reservations,
		@Schema(description = "다음 페이지 조회 정보", requiredMode = Schema.RequiredMode.REQUIRED)
		CursorResponse.PageInfo pageInfo
	) {
		public static HostReservationInfos from(
			List<HostReservationInfo> reservationInfos,
			CursorResponse.PageInfo pageInfo) {

			return HostReservationInfos.builder()
				.reservations(reservationInfos)
				.pageInfo(pageInfo)
				.build();
		}
	}

	@Builder
	@Schema(name = "ReservationResponseHostDetail", example = """
		{
		  "reservation_uid": "00000000-0000-0000-0000-000000000020",
		  "reservation_code": "AB12CD",
		  "status": "CONFIRMED",
		  "created_at": "2027-01-01T00:00:00Z",
		  "guest_count": 2,
		  "check_in_date_time": "2027-01-10T15:00:00",
		  "check_out_date_time": "2027-01-12T11:00:00",
		  "time_zone_id": "Asia/Seoul",
		  "request_message": "늦은 체크인 예정입니다.",
		  "accommodation": {
		    "id": 101,
		    "name": "도심 속 작은 숙소",
		    "thumbnail_url": null
		  },
		  "address": {
		    "country": "대한민국",
		    "state": "서울특별시",
		    "city": "서울",
		    "district": "종로구",
		    "street": "가상로 123",
		    "detail": "101호",
		    "postal_code": "03000"
		  },
		  "payment": {
		    "total_amount": 190000
		  },
		  "guest": {
		    "id": 20,
		    "nickname": "여행자",
		    "thumbnail_image_url": null
		  }
		}
		""")
	public record HostDetail(
		@Schema(description = "예약 UID. 예약 상세·보유·취소 경로와 결제 주문 ID에 사용합니다.", example = "00000000-0000-0000-0000-000000000020", requiredMode = Schema.RequiredMode.REQUIRED)
		String reservationUid,
		@Schema(description = "화면에 표시하는 예약 코드. API 경로에는 reservation_uid를 사용합니다.", example = "AB12CD", requiredMode = Schema.RequiredMode.REQUIRED)
		String reservationCode,
		@Schema(description = "DB에 저장된 예약 상태", example = "CONFIRMED", requiredMode = Schema.RequiredMode.REQUIRED)
		ReservationStatus status,
		@Schema(description = "예약 생성 UTC 시각. 과거 데이터는 null일 수 있습니다.", example = "2027-01-01T00:00:00Z", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Instant createdAt,
		@Schema(example = "2", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		Integer guestCount,
		@Schema(type = "string", format = "local-date-time", description = "예약에 저장한 숙소 현지 체크인 일시. time_zone_id와 함께 읽습니다.", example = "2027-01-10T15:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDateTime checkInDateTime,
		@Schema(type = "string", format = "local-date-time", description = "예약에 저장한 숙소 현지 체크아웃 일시. time_zone_id와 함께 읽습니다.", example = "2027-01-12T11:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
		LocalDateTime checkOutDateTime,
		@Schema(description = "예약에 저장한 IANA 시간대", example = "Asia/Seoul", requiredMode = Schema.RequiredMode.REQUIRED)
		String timeZoneId,
		@Schema(description = "체크아웃 시 게스트가 보낸 요청 사항", example = "늦은 체크인 예정입니다.", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String requestMessage,
		@Schema(description = "숙소의 현재 기본 정보", requiredMode = Schema.RequiredMode.REQUIRED)
		AccommodationResponse.AccommodationBasicInfo accommodation,
		@Schema(description = "숙소의 현재 주소", requiredMode = Schema.RequiredMode.REQUIRED)
		AddressResponse.AddressInfo address,
		@Schema(description = "게스트의 현재 회원 정보", requiredMode = Schema.RequiredMode.REQUIRED)
		MemberResponse.MemberInfo guest,
		@Schema(description = "결제 기록. 미결제·0원 예약 등 기록이 없으면 null입니다.", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		PaymentResponse.HostPaymentInfo payment
	) {
		public static HostDetail from(HostReservationDetailProjection detail) {
			ZoneId timeZone = ZoneId.of(detail.timeZoneId());
			return HostDetail.builder()
				.reservationUid(detail.reservationUid().toString())
				.reservationCode(detail.reservationCode())
				.status(detail.status())
				.createdAt(toUtcInstant(detail.createdAt()))
				.guestCount(detail.guestCount())
				.checkInDateTime(LocalDateTime.ofInstant(detail.checkInAt(), timeZone))
				.checkOutDateTime(LocalDateTime.ofInstant(detail.checkOutAt(), timeZone))
				.timeZoneId(detail.timeZoneId())
				.requestMessage(detail.requestMessage())
				.accommodation(new AccommodationResponse.AccommodationBasicInfo(
					detail.accommodationId(), detail.accommodationName(), detail.accommodationThumbnailUrl()))
				.address(new AddressResponse.AddressInfo(detail.country(), detail.state(), detail.city(),
					detail.district(), detail.street(), detail.addressDetail(), detail.postalCode()))
				.guest(new MemberResponse.MemberInfo(
					detail.guestId(), detail.guestNickname(), detail.guestThumbnailImageUrl()))
				.payment(detail.paymentAmount() == null ? null : new PaymentResponse.HostPaymentInfo(detail.paymentAmount()))
				.build();
		}
	}

	private static Instant toUtcInstant(LocalDateTime dateTime) {
		return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
	}
}
