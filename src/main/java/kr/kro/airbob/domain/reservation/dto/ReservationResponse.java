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
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationQuote;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
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

	@Builder
	public record GuestReservationInfo(
		long reservationId,
		String reservationUid,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		String timeZoneId,
		ReservationStatus status,
		// Integer totalPrice,
		Instant createdAt,

		AccommodationResponse.AccommodationBasicInfo accommodation
	) {
		public static GuestReservationInfo from(Reservation reservation, Instant serverTime) {

			return GuestReservationInfo.builder()
				.reservationId(reservation.getId())
				.reservationUid(reservation.getReservationUid().toString())
				.checkInDate(reservation.getCheckInDate())
				.checkOutDate(reservation.getCheckOutDate())
				.timeZoneId(reservation.getTimeZoneId())
				.status(reservation.effectiveStatus(serverTime))
				// .totalPrice(reservation.getTotalPrice())
				.createdAt(toUtcInstant(reservation.getCreatedAt()))
				.accommodation(
					AccommodationResponse.AccommodationBasicInfo.from(reservation.getAccommodation()))
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

		PaymentResponse.PaymentInfo payment
	) {
		public static GuestDetail from(Reservation reservation,
			PaymentResponse.PaymentInfo paymentInfo,
			boolean canWriteReview,
			Instant serverTime) {
			Accommodation accommodation = reservation.getAccommodation();
			Address address = accommodation.getAddress();
			Member host = accommodation.getMember();
			ZoneId timeZone = ZoneId.of(reservation.getTimeZoneId());
			LocalDateTime checkInDateTime = LocalDateTime.ofInstant(reservation.getCheckInAt(), timeZone);
			LocalDateTime checkOutDateTime = LocalDateTime.ofInstant(reservation.getCheckOutAt(), timeZone);

			return GuestDetail.builder()
				.reservationUid(reservation.getReservationUid().toString())
				.reservationCode(reservation.getReservationCode())
				.status(reservation.effectiveStatus(serverTime))
				.paymentAllowed(reservation.isPaymentAllowedAt(serverTime))
				.holdExpiresAt(activeHoldExpiresAt(reservation))
				.serverTime(serverTime)
				.createdAt(toUtcInstant(reservation.getCreatedAt()))
				.guestCount(reservation.getGuestCount())
				.checkInDateTime(checkInDateTime)
				.checkOutDateTime(checkOutDateTime)
				.timeZoneId(reservation.getTimeZoneId())
				.checkInTime(checkInDateTime.toLocalTime())
				.checkOutTime(checkOutDateTime.toLocalTime())
				.requestMessage(reservation.getMessage())
				.canWriteReview(canWriteReview)
				.accommodation(AccommodationResponse.AccommodationBasicInfo.from(accommodation))
				.address(AddressResponse.AddressInfo.from(address))
				.coordinate(AddressResponse.Coordinate.from(address))
				.host(MemberResponse.MemberInfo.from(host))
				.payment(paymentInfo)
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
		public static HostReservationInfo from(Reservation reservation) {
			return HostReservationInfo.builder()
				.reservationUid(reservation.getReservationUid().toString())
				.reservationCode(reservation.getReservationCode())
				.totalPrice(reservation.getTotalPrice())
				.currency(reservation.getCurrency())
				.guestCount(reservation.getGuestCount())
				.checkInDate(reservation.getCheckInDate())
				.checkOutDate(reservation.getCheckOutDate())
				.timeZoneId(reservation.getTimeZoneId())
				.status(reservation.getStatus())
				.createdAt(toUtcInstant(reservation.getCreatedAt()))
				.guest(MemberResponse.MemberInfo.from(reservation.getGuest()))
				.accommodation(
					AccommodationResponse.AccommodationBasicInfo.from(reservation.getAccommodation()))
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

		PaymentResponse.PaymentInfo payment
	) {
		public static HostDetail from(Reservation reservation, PaymentResponse.PaymentInfo paymentInfo) {
			Accommodation accommodation = reservation.getAccommodation();
			Address address = accommodation.getAddress();
			ZoneId timeZone = ZoneId.of(reservation.getTimeZoneId());
			return HostDetail.builder()
				.reservationUid(reservation.getReservationUid().toString())
				.reservationCode(reservation.getReservationCode())
				.status(reservation.getStatus())
				.createdAt(toUtcInstant(reservation.getCreatedAt()))
				.guestCount(reservation.getGuestCount())
				.checkInDateTime(LocalDateTime.ofInstant(reservation.getCheckInAt(), timeZone))
				.checkOutDateTime(LocalDateTime.ofInstant(reservation.getCheckOutAt(), timeZone))
				.timeZoneId(reservation.getTimeZoneId())
				.requestMessage(reservation.getMessage())
				.accommodation(
					AccommodationResponse.AccommodationBasicInfo.from(accommodation))
				.address(AddressResponse.AddressInfo.from(address))
				.guest(MemberResponse.MemberInfo.from(reservation.getGuest()))
				.payment(paymentInfo)
				.build();
		}
	}

	private static Instant toUtcInstant(LocalDateTime dateTime) {
		return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
	}
}
