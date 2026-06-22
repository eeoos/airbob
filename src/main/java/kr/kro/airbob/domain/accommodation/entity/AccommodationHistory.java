package kr.kro.airbob.domain.accommodation.entity;

import java.time.LocalDateTime;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.common.history.MasterHistoryBase;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// 숙소 이력 — 전체 행 스냅샷(Master, valid_from/valid_to, SCD2).
// address·occupancy_policy는 owned 1:1 값 객체라 필드를 펼쳐 스냅샷한다. description도 복원 위해 포함.
@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccommodationHistory extends MasterHistoryBase {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long accommodationId;

	// --- accommodation 본체 스냅샷 ---
	@Column(length = 36)
	private String accommodationUid;
	private String name;
	@Column(columnDefinition = "TEXT")
	private String description;
	private Long basePrice;
	@Column(length = 3)
	private String currency;
	private String thumbnailUrl;
	private String type;
	@Enumerated(EnumType.STRING)
	private AccommodationStatus status;
	private LocalTime checkInTime;
	private LocalTime checkOutTime;
	private Long memberId;

	// --- address(owned 1:1) 스냅샷 ---
	private String addressCountry;
	private String addressState;
	private String addressCity;
	private String addressDistrict;
	private String addressStreet;
	private String addressDetail;
	private String addressPostalCode;
	private Double addressLatitude;
	private Double addressLongitude;

	// --- occupancy_policy(owned 1:1) 스냅샷 ---
	private Integer maxOccupancy;
	private Integer infantOccupancy;
	private Integer petOccupancy;

	// --- 원본 최초 생성 정보(스냅샷) ---
	private LocalDateTime createdAt;
	private Long createdBy;

	// 숙소 변경은 모두 인증된 호스트 요청 → source_system/client_ip를 요청 컨텍스트에서 채움
	public static AccommodationHistory of(Accommodation a, ChangeType changeType, String changeReason) {
		Address addr = a.getAddress();
		OccupancyPolicy occ = a.getOccupancyPolicy();
		return AccommodationHistory.builder()
			.accommodationId(a.getId())
			.accommodationUid(a.getAccommodationUid() == null ? null : a.getAccommodationUid().toString())
			.name(a.getName())
			.description(a.getDescription())
			.basePrice(a.getBasePrice())
			.currency(a.getCurrency())
			.thumbnailUrl(a.getThumbnailUrl())
			.type(a.getType())
			.status(a.getStatus())
			.checkInTime(a.getCheckInTime())
			.checkOutTime(a.getCheckOutTime())
			.memberId(a.getMember() == null ? null : a.getMember().getId())
			.addressCountry(addr == null ? null : addr.getCountry())
			.addressState(addr == null ? null : addr.getState())
			.addressCity(addr == null ? null : addr.getCity())
			.addressDistrict(addr == null ? null : addr.getDistrict())
			.addressStreet(addr == null ? null : addr.getStreet())
			.addressDetail(addr == null ? null : addr.getDetail())
			.addressPostalCode(addr == null ? null : addr.getPostalCode())
			.addressLatitude(addr == null ? null : addr.getLatitude())
			.addressLongitude(addr == null ? null : addr.getLongitude())
			.maxOccupancy(occ == null ? null : occ.getMaxOccupancy())
			.infantOccupancy(occ == null ? null : occ.getInfantOccupancy())
			.petOccupancy(occ == null ? null : occ.getPetOccupancy())
			.createdAt(a.getCreatedAt())
			.createdBy(a.getCreatedBy())
			.changeType(changeType)
			.changeReason(changeReason)
			.sourceSystem(UserContext.currentSourceSystem())
			.clientIp(UserContext.currentClientIp())
			.validFrom(LocalDateTime.now())
			.validTo(HistoryConstants.FOREVER)
			.build();
	}
}
