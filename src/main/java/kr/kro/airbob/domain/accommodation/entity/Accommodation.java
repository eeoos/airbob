package kr.kro.airbob.domain.accommodation.entity;

import java.time.LocalTime;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import kr.kro.airbob.common.domain.UpdatableEntity;
import kr.kro.airbob.domain.accommodation.common.AccommodationType;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.Update;
import kr.kro.airbob.domain.member.entity.Member;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Accommodation extends UpdatableEntity {

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

	@Enumerated(EnumType.STRING)
	private AccommodationType type;

	@OneToOne(fetch = FetchType.LAZY, orphanRemoval = true, cascade = CascadeType.ALL)
	private Address address;

	@OneToOne(fetch = FetchType.LAZY, orphanRemoval = true, cascade = CascadeType.ALL)
	private OccupancyPolicy occupancyPolicy;

	@OneToOne(fetch = FetchType.LAZY)
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

	public void updateAccommodation(Update request) {
		if (request.name() != null) {
			this.name = request.name();
		}

		if (request.description() != null) {
			this.description = request.description();
		}

		if (request.basePrice() != null) {
			this.basePrice = request.basePrice();
		}

		if (request.currency() != null) {
			// this.currency = request.currency();
			// todo: 국제화를 고려하지 못하여 KRW로 하드코딩
			this.currency = "KRW";
		}

		if (request.type() != null) {
			this.type = AccommodationType.valueOf(request.type().toUpperCase());
		}

		if (request.checkInTime() != null) {
			this.checkInTime =request.checkInTime();
		}

		if (request.checkOutTime() != null) {
			this.checkOutTime =request.checkOutTime();
		}
	}

	public void updateAddress(Address address) {
		this.address = address;
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
}
