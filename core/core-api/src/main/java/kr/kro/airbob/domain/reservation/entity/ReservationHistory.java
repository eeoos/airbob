package kr.kro.airbob.domain.reservation.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.history.HistoryBase;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// 예약 이력 — 전체 행 스냅샷(Transaction 성격, INSERT-only, 유효기간 없음).
// 직전 상태는 직전 이력 행으로 표현되며, change_type이 이번 변경의 성격을 나타냄
@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationHistory extends HistoryBase {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long reservationId;

	// --- 원본 비즈니스 컬럼 스냅샷 ---
	@Column(length = 36)
	private String reservationUid;
	@Column(length = 10)
	private String reservationCode;
	private Long accommodationId;
	private Long guestId;
	private LocalDate checkInDate;
	private LocalDate checkOutDate;
	private Instant checkInAt;
	private Instant checkOutAt;
	@Column(length = 64)
	private String timeZoneId;
	private Integer guestCount;
	private Long totalPrice;
	@Column(length = 3)
	private String currency;
	@Enumerated(EnumType.STRING)
	private ReservationStatus status;
	@Column(length = 500)
	private String message;
	private Instant expiresAt;

	// --- 원본 최초 생성 정보(스냅샷) ---
	private LocalDateTime createdAt;
	private Long createdBy;

	// 사용자 요청에서 비롯된 변경: source_system/client_ip를 요청 컨텍스트에서 채움
	public static ReservationHistory of(Reservation reservation, ChangeType changeType, String changeReason) {
		return build(reservation, changeType, changeReason,
			UserContext.currentSourceSystem(), UserContext.currentClientIp());
	}

	// 시스템/컨슈머/배치에서 비롯된 변경: source_system을 명시 (client_ip 없음)
	public static ReservationHistory ofSystem(Reservation reservation, ChangeType changeType, String changeReason,
		String sourceSystem) {
		return build(reservation, changeType, changeReason, sourceSystem, null);
	}

	private static ReservationHistory build(Reservation r, ChangeType changeType, String changeReason,
		String sourceSystem, String clientIp) {
		return ReservationHistory.builder()
			.reservationId(r.getId())
			.reservationUid(r.getReservationUid() == null ? null : r.getReservationUid().toString())
			.reservationCode(r.getReservationCode())
			.accommodationId(r.getAccommodation() == null ? null : r.getAccommodation().getId())
			.guestId(r.getGuest() == null ? null : r.getGuest().getId())
			.checkInDate(r.getCheckInDate())
			.checkOutDate(r.getCheckOutDate())
			.checkInAt(r.getCheckInAt())
			.checkOutAt(r.getCheckOutAt())
			.timeZoneId(r.getTimeZoneId())
			.guestCount(r.getGuestCount())
			.totalPrice(r.getTotalPrice())
			.currency(r.getCurrency())
			.status(r.getStatus())
			.message(r.getMessage())
			.expiresAt(r.getExpiresAt())
			.createdAt(r.getCreatedAt())
			.createdBy(r.getCreatedBy())
			.changeType(changeType)
			.changeReason(changeReason)
			.sourceSystem(sourceSystem)
			.clientIp(clientIp)
			.build();
	}
}
