package kr.kro.airbob.domain.reservation.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

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
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationResponse {

	@Builder
	public record Ready(
		String reservationUid, // toss orderId
		String orderName,
		Long amount,
		String customerEmail,
		String customerName
	) {
		public static Ready from(Reservation reservation) {
			return Ready.builder()
				.reservationUid(reservation.getReservationUid().toString())
				.orderName(reservation.getAccommodation().getName())
				.amount(reservation.getTotalPrice())
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
		// Integer totalPrice,
		LocalDateTime createdAt,

		AccommodationResponse.AccommodationBasicInfo accommodation
	){
		public static GuestReservationInfo from(Reservation reservation) {

			return GuestReservationInfo.builder()
				.reservationId(reservation.getId())
				.reservationUid(reservation.getReservationUid().toString())
				.checkInDate(reservation.getCheckIn().toLocalDate())
				.checkOutDate(reservation.getCheckOut().toLocalDate())
				// .totalPrice(reservation.getTotalPrice())
				.createdAt(reservation.getCreatedAt())
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
		LocalDateTime createdAt,
		Integer guestCount,
		LocalDateTime checkInDateTime,
		LocalDateTime checkOutDateTime,
		LocalTime checkInTime,
		LocalTime checkOutTime,
		Boolean canWriteReview,
		AccommodationResponse.AccommodationBasicInfo accommodation,
		AddressResponse.AddressInfo address,
		AddressResponse.Coordinate coordinate,
		MemberResponse.MemberInfo host,

		PaymentResponse.PaymentInfo payment
	) {
		public static GuestDetail from(Reservation reservation,
			PaymentResponse.PaymentInfo paymentInfo,
			boolean canWriteReview) {
			Accommodation accommodation = reservation.getAccommodation();
			Address address = accommodation.getAddress();
			Member host = accommodation.getMember();

			return GuestDetail.builder()
				.reservationUid(reservation.getReservationUid().toString())
				.reservationCode(reservation.getReservationCode())
				.status(reservation.getStatus())
				.createdAt(reservation.getCreatedAt())
				.guestCount(reservation.getGuestCount())
				.checkInDateTime(reservation.getCheckIn())
				.checkOutDateTime(reservation.getCheckOut())
				.checkInTime(reservation.getCheckIn().toLocalTime())
				.checkOutTime(reservation.getCheckOut().toLocalTime())
				.canWriteReview(canWriteReview)
				.accommodation(AccommodationResponse.AccommodationBasicInfo.from(accommodation))
				.address(AddressResponse.AddressInfo.from(address))
				.coordinate(AddressResponse.Coordinate.from(address))
				.host(MemberResponse.MemberInfo.from(host))
				.payment(paymentInfo)
				.build();
		}
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
		ReservationStatus status,
		LocalDateTime createdAt,

		MemberResponse.MemberInfo guest,
		AccommodationResponse.AccommodationBasicInfo accommodation
	){
		public static HostReservationInfo from(Reservation reservation) {
			return HostReservationInfo.builder()
				.reservationUid(reservation.getReservationUid().toString())
				.reservationCode(reservation.getReservationCode())
				.totalPrice(reservation.getTotalPrice())
				.currency(reservation.getCurrency())
				.guestCount(reservation.getGuestCount())
				.checkInDate(reservation.getCheckIn().toLocalDate())
				.checkOutDate(reservation.getCheckOut().toLocalDate())
				.status(reservation.getStatus())
				.createdAt(reservation.getCreatedAt())
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
		LocalDateTime createdAt,
		Integer guestCount,
		LocalDateTime checkInDateTime,
		LocalDateTime checkOutDateTime,

		AccommodationResponse.AccommodationBasicInfo accommodation,
		AddressResponse.AddressInfo address,

		MemberResponse.MemberInfo guest,

		PaymentResponse.PaymentInfo payment
	) {
		public static HostDetail from(Reservation reservation, PaymentResponse.PaymentInfo paymentInfo) {
			Accommodation accommodation = reservation.getAccommodation();
			Address address = accommodation.getAddress();
			return HostDetail.builder()
				.reservationUid(reservation.getReservationUid().toString())
				.reservationCode(reservation.getReservationCode())
				.status(reservation.getStatus())
				.createdAt(reservation.getCreatedAt())
				.guestCount(reservation.getGuestCount())
				.checkInDateTime(reservation.getCheckIn())
				.checkOutDateTime(reservation.getCheckOut())
				.accommodation(
					AccommodationResponse.AccommodationBasicInfo.from(accommodation))
				.address(AddressResponse.AddressInfo.from(address))
				.guest(MemberResponse.MemberInfo.from(reservation.getGuest()))
				.payment(paymentInfo)
				.build();
		}
	}
}
