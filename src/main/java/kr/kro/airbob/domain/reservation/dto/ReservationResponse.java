package kr.kro.airbob.domain.reservation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationQuote;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.repository.projection.GuestReservationListProjection;
import kr.kro.airbob.domain.reservation.repository.projection.GuestReservationDetailProjection;
import kr.kro.airbob.domain.reservation.repository.projection.HostReservationDetailProjection;
import kr.kro.airbob.domain.reservation.repository.projection.HostReservationListProjection;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationResponse {

	@Builder
	public record Quote(
		UUID quoteUid,
		Long accommodationId,
		String orderName,
		LocalDate checkIn,
		LocalDate checkOut,
		Integer guestCount,
		Long nightlyPrice,
		Long nights,
		Long subtotal,
		Long discountAmount,
		Long amount,
		String currency,
		boolean paymentRequired,
		boolean inventoryHeld,
		Instant quoteExpiresAt,
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
				.quoteExpiresAt(quote.getExpiresAt())
				.serverTime(serverTime)
				.build();
		}
	}

	@Builder
	public record Ready(
		String reservationUid, // toss orderId
		String orderName,
		LocalDate checkIn,
		LocalDate checkOut,
		Integer guestCount,
		Long subtotal,
		Long discountAmount,
		Long amount,
		String currency,
		ReservationStatus status,
		boolean paymentRequired,
		boolean paymentAllowed,
		Instant holdExpiresAt,
		Instant serverTime,
		String customerEmail,
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

	public record HoldRelease(
		String reservationUid,
		ReservationStatus status,
		boolean releasedNow,
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

	public record PaymentAttemptReady(
		UUID paymentAttemptId,
		String orderId,
		long amount,
		String currency,
		Instant holdExpiresAt,
		long remainingSeconds,
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
	public record GuestReservationInfo(
		long reservationId,
		String reservationUid,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		String timeZoneId,
		ReservationStatus status,
		Instant createdAt,

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
	public record GuestReservationInfos(
		List<GuestReservationInfo> reservations,
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
	public record GuestDetail(
		String reservationUid,
		String reservationCode,
		ReservationStatus status,
		boolean paymentAllowed,
		Instant holdExpiresAt,
		Instant serverTime,
		Instant createdAt,
		Integer guestCount,
		LocalDateTime checkInDateTime,
		LocalDateTime checkOutDateTime,
		String timeZoneId,
		LocalTime checkInTime,
		LocalTime checkOutTime,
		String requestMessage,
		Boolean canWriteReview,
		AccommodationResponse.AccommodationBasicInfo accommodation,
		AddressResponse.AddressInfo address,
		AddressResponse.Coordinate coordinate,
		MemberResponse.MemberInfo host,

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
	public record HostReservationInfo(
		String reservationUid,
		String reservationCode,
		Long totalPrice,
		String currency,
		int guestCount,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		String timeZoneId,
		ReservationStatus status,
		Instant createdAt,

		MemberResponse.MemberInfo guest,
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
	public record HostReservationInfos(
		List<HostReservationInfo> reservations,
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
	public record HostDetail(
		String reservationUid,
		String reservationCode,
		ReservationStatus status,
		Instant createdAt,
		Integer guestCount,
		LocalDateTime checkInDateTime,
		LocalDateTime checkOutDateTime,
		String timeZoneId,
		String requestMessage,

		AccommodationResponse.AccommodationBasicInfo accommodation,
		AddressResponse.AddressInfo address,

		MemberResponse.MemberInfo guest,

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
