package kr.kro.airbob.core.domain.accommodation;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import kr.kro.airbob.core.domain.member.Member;
import kr.kro.airbob.core.support.persistence.BaseEntity;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Accommodation extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(nullable = false, unique = true, updatable = false, columnDefinition = "BINARY(16)")
	private UUID accommodationUid;
	private String name;

	private String description;

	private Long basePrice;

	@Column(length = 3)
	private String currency;

	private String thumbnailUrl;

	// 공통 코드(ACCOMMODATION_TYPE)의 code 값. FK 없이 보관, 검증은 애플리케이션 레벨(CommonCodeService).
	private String type;

	@OneToOne(fetch = FetchType.LAZY, orphanRemoval = true, cascade = CascadeType.ALL)
	private Address address;

	@Column(length = 64)
	private String timeZoneId;

	@OneToOne(fetch = FetchType.LAZY, orphanRemoval = true, cascade = CascadeType.ALL)
	private OccupancyPolicy occupancyPolicy;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(nullable = false)
	private LocalTime checkInTime;

	@Column(nullable = false)
	private LocalTime checkOutTime;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AccommodationStatus status;

	@PrePersist
	protected void onCreate() {
		if (this.accommodationUid == null) {
			this.accommodationUid = UUID.randomUUID();
		}
		if (this.status == null) {
			this.status = AccommodationStatus.DRAFT; // 생성 시 기본 상태
		}
	}

	public static Accommodation createAccommodation(Member member) {
		return Accommodation.builder()
			.checkInTime(LocalTime.of(15, 0)) // 기본값: 3시[
			.checkOutTime(LocalTime.of(11, 0)) // 기본값: 11시
			.member(member)
			.build();
	}

	public void updateAccommodation(AccommodationPatch patch) {
		if (patch.name() != null) {
			this.name = patch.name();
		}

		if (patch.description() != null) {
			this.description = patch.description();
		}

		if (patch.basePrice() != null) {
			this.basePrice = patch.basePrice();
		}

		if (patch.currency() != null) {
			// this.currency = patch.currency();
			// todo: 국제화를 고려하지 못하여 KRW로 하드코딩
			this.currency = "KRW";
		}

		if (patch.type() != null) {
			// 유효성은 서비스 계층(CommonCodeService.isValidCode)에서 사전 검증됨
			this.type = patch.type().toUpperCase(Locale.ROOT);
		}

		if (patch.checkInTime() != null) {
			this.checkInTime = patch.checkInTime();
		}

		if (patch.checkOutTime() != null) {
			this.checkOutTime = patch.checkOutTime();
		}
	}

	public void updateLocation(Address address, ZoneId timeZone) {
		this.address = Objects.requireNonNull(address);
		this.timeZoneId = Objects.requireNonNull(timeZone).getId();
	}

	public void updateOccupancyPolicy(OccupancyPolicy occupancyPolicy) {
		this.occupancyPolicy = occupancyPolicy;
	}

	public void publish() {
		this.status = AccommodationStatus.PUBLISHED;
	}

	public void unpublish() {
		this.status = AccommodationStatus.UNPUBLISHED;
	}

	public void delete() {
		this.status = AccommodationStatus.DELETED;
	}

	public void updateThumbnailUrl(String imageUrl) {
		this.thumbnailUrl = imageUrl;
	}

	public String bookingCurrency() {
		return currency == null || currency.isBlank() ? "KRW" : currency;
	}
}
