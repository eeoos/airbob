package kr.kro.airbob.domain.reservation.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import io.reactivex.rxjava3.internal.operators.flowable.FlowableFromCallable;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationResponse {

	@Builder
	public record Create(
		long reservationId,
		long accommodationId,
		String accommodationName,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		int totalPrice,
		ReservationStatus status
	){
		public static Create from(Reservation reservation) {
			return Create.builder()
				.reservationId(reservation.getId())
				.accommodationId(reservation.getAccommodation().getId())
				.accommodationName(reservation.getAccommodation().getName())
				.checkInDate(reservation.getCheckIn().toLocalDate())
				.checkOutDate(reservation.getCheckOut().toLocalDate())
				.totalPrice(reservation.getTotalPrice())
				.status(reservation.getStatus())
				.build();
		}
	}

	@Builder
	public record Ready(
		String reservationUid, // toss orderId
		String orderName,
		Integer amount,
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
	public record MyReservationInfo(
		long reservationId,
		String reservationUid,
		ReservationStatus status,

		Long accommodationId,
		String accommodationName,
		String accommodationThumbnailUrl,
		String accommodationLocation,

		LocalDate checkInDate,
		LocalDate checkOutDate,

		Integer totalPrice,

		LocalDateTime createdAt
	){
		public static MyReservationInfo from(Reservation reservation) {
			Accommodation accommodation = reservation.getAccommodation();
			Address address = accommodation.getAddress();

			String location = (address.getCity() != null ? address.getCity() : "") +
				(address.getDistrict() != null ? " " + address.getDistrict() : "");

			return MyReservationInfo.builder()
				.reservationId(reservation.getId())
				.reservationUid(reservation.getReservationUid().toString())
				.status(reservation.getStatus())
				.accommodationId(accommodation.getId())
				.accommodationName(accommodation.getName())
				.accommodationThumbnailUrl(accommodation.getThumbnailUrl())
				.accommodationLocation(location.trim())
				.checkInDate(reservation.getCheckIn().toLocalDate())
				.checkOutDate(reservation.getCheckOut().toLocalDate())
				.totalPrice(reservation.getTotalPrice())
				.createdAt(reservation.getCreatedAt())
				.build();
		}
	}

	@Builder
	public record MyReservationInfos(
		List<MyReservationInfo> reservations,
		CursorResponse.PageInfo pageInfo
	) {
	}

	@Builder
	public record DetailInfo(
		String reservationUid,
		ReservationStatus status,
		LocalDateTime createdAt,
		Integer guestCount,
		String message,

		Long accommodationId,
		String accommodationName,
		String accommodationThumbnailUrl,
		AccommodationAddressInfo accommodationAddress,
		AccommodationHostInfo accommodationHost,

		LocalDateTime checkInDateTime,
		LocalDateTime checkOutDateTime,
		LocalTime checkInTime,
		LocalTime checkOutTime,

		PaymentResponse.PaymentInfo paymentInfo
	) {
	}

	@Builder
	public record AccommodationAddressInfo(
		String country,
		String city,
		String district,
		String street,
		String detail,
		String postalCode,
		String fullAddress,
		Double latitude,
		Double longitude
	) {
	}

	@Builder
	public record AccommodationHostInfo(
		Long id,
		String nickname
	) {
	}

	@Builder
	public record AccommodationGuestInfo(
		Long id,
		String nickname
	) {
	}

	@Builder
	public record GuestInfo(
		Long id,
		String nickname
	) {
	}

	@Builder
	public record HostReservationInfo(
		String reservationUid,
		ReservationStatus status,
		AccommodationGuestInfo hostInfo,
		int guestCount,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		LocalDateTime createdAt,
		Long accommodationId,
		String accommodationName,
		String thumbnailUrl,
		String confirmationCode,
		Integer totalPrice
	){
	}

	@Builder
	public record HostReservationInfos(
		List<HostReservationInfo> reservations,
		CursorResponse.PageInfo pageInfo
	) {
	}

	@Builder
	public record HostDetailInfo(
		String reservationUid,
		ReservationStatus status,
		LocalDateTime createdAt,
		Integer guestCount,
		String message,

		Long accommodationId,
		String accommodationName,

		LocalDateTime checkInDateTime,
		LocalDateTime checkOutDateTime,

		GuestInfo guestInfo,

		PaymentResponse.PaymentInfo paymentInfo
	) {
	}
}
