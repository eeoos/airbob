package kr.kro.airbob.domain.accommodation.service;

import static kr.kro.airbob.search.event.AccommodationIndexingEvents.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.AddressRequest;
import kr.kro.airbob.domain.accommodation.dto.AmenityRequest;
import kr.kro.airbob.domain.accommodation.dto.PolicyRequest;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.AccommodationHistory;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.exception.AccommodationLocationResolutionException;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.exception.AccommodationStateException;
import kr.kro.airbob.domain.accommodation.exception.InvalidAccommodationAmenityException;
import kr.kro.airbob.domain.accommodation.exception.InvalidAccommodationTypeException;
import kr.kro.airbob.domain.accommodation.exception.PublishingFieldRequiredException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationHistoryRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.AddressRepository;
import kr.kro.airbob.domain.accommodation.repository.OccupancyPolicyRepository;
import kr.kro.airbob.domain.commoncode.common.CommonCodeGroups;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import kr.kro.airbob.domain.image.exception.InsufficientImageCountException;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.geo.GeocodingService;
import kr.kro.airbob.geo.TimeZoneResolver;
import kr.kro.airbob.geo.dto.GeocodeResult;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccommodationCommandService {

	private final AccommodationAmenityRepository accommodationAmenityRepository;
	private final AccommodationImageRepository accommodationImageRepository;
	private final AccommodationHistoryRepository accommodationHistoryRepository;
	private final OccupancyPolicyRepository occupancyPolicyRepository;
	private final AccommodationRepository accommodationRepository;
	private final ReservationRepository reservationRepository;
	private final CommonCodeService commonCodeService;
	private final AddressRepository addressRepository;
	private final MemberRepository memberRepository;
	private final OutboxEventPublisher outboxEventPublisher;
	private final GeocodingService geocodingService;
	private final TimeZoneResolver timeZoneResolver;
	private final AccommodationDetailCacheInvalidationPublisher cacheInvalidationPublisher;
	private final Clock clock;

	@Transactional
	public AccommodationResponse.Create createAccommodation(Long memberId) {
		Member member = memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE)
			.orElseThrow(MemberNotFoundException::new);

		Accommodation accommodation = Accommodation.createAccommodation(member);
		Accommodation savedAccommodation = accommodationRepository.save(accommodation);

		recordHistory(savedAccommodation, ChangeType.CREATE, "숙소 생성");

		return new AccommodationResponse.Create(savedAccommodation.getId());
	}

	@Transactional
	public void updateAccommodation(Long accommodationId, AccommodationRequest.Update request, Long memberId) {
		Accommodation accommodation = findByIdAndMemberIdAndStatusNot(accommodationId, memberId);

		validateAccommodationType(request.type());
		validateTurnoverTimes(accommodation, request);
		Map<String, Integer> amenityCountMap = getAmenityCountMap(request.amenityInfos());
		ResolvedLocation resolvedLocation = resolveLocation(accommodation, request.addressInfo());
		validateTemporalSettingsChange(accommodation, request, resolvedLocation);
		accommodation.updateAccommodation(request);
		updateLocation(accommodation, resolvedLocation);
		updateOccupancyPolicy(accommodation, request.occupancyPolicyInfo());
		updateAmenities(accommodation, amenityCountMap);

		recordHistory(accommodation, ChangeType.UPDATE, "숙소 정보 수정");

		if (accommodation.getStatus() == AccommodationStatus.PUBLISHED) {
			outboxEventPublisher.save(
				EventType.ACCOMMODATION_UPDATED,
				new AccommodationUpdatedEvent(accommodation.getAccommodationUid().toString())
			);
		}

		cacheInvalidationPublisher.publish(
			accommodationId, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
	}

	@Transactional
	public void deleteAccommodation(Long accommodationId, Long memberId) {
		Accommodation accommodation = findByIdAndMemberIdExceptDeleted(accommodationId, memberId);

		accommodation.delete();
		recordHistory(accommodation, ChangeType.DELETE, "숙소 삭제");

		outboxEventPublisher.save(
			EventType.ACCOMMODATION_DELETED,
			new AccommodationDeletedEvent(accommodation.getAccommodationUid().toString())
		);
		cacheInvalidationPublisher.publish(
			accommodationId, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
	}

	@Transactional
	public void publishAccommodation(Long accommodationId, Long memberId) {
		Accommodation accommodation = findWithDetailsExceptHostAndDeletedById(accommodationId, memberId);

		validateAccommodationForPublishing(accommodation);
		accommodation.publish();
		recordHistory(accommodation, ChangeType.STATUS_CHANGE, "숙소 게시");

		outboxEventPublisher.save(
			EventType.ACCOMMODATION_UPDATED,
			new AccommodationUpdatedEvent(accommodation.getAccommodationUid().toString())
		);
		cacheInvalidationPublisher.publish(
			accommodationId, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
	}

	@Transactional
	public void unpublishAccommodation(Long accommodationId, Long memberId) {
		Accommodation accommodation = findByIdAndMemberIdExceptDeleted(accommodationId, memberId);

		if (accommodation.getStatus() != AccommodationStatus.PUBLISHED) {
			throw new AccommodationStateException();
		}

		accommodation.unpublish();
		recordHistory(accommodation, ChangeType.STATUS_CHANGE, "숙소 게시 중단");

		outboxEventPublisher.save(
			EventType.ACCOMMODATION_UPDATED,
			new AccommodationUpdatedEvent(accommodation.getAccommodationUid().toString())
		);
		cacheInvalidationPublisher.publish(
			accommodationId, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
	}

	private void recordHistory(Accommodation accommodation, ChangeType changeType, String reason) {
		LocalDateTime changedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		accommodationHistoryRepository.findByAccommodationIdAndValidTo(
			accommodation.getId(), HistoryConstants.FOREVER
		).ifPresent(current -> current.close(changedAt));
		accommodationHistoryRepository.save(
			AccommodationHistory.of(accommodation, changeType, reason, changedAt));
	}

	private void validateAccommodationForPublishing(Accommodation accommodation) {
		if (accommodation.getName() == null || accommodation.getName().isBlank()) {
			throw new PublishingFieldRequiredException("name");
		}
		if (accommodation.getDescription() == null || accommodation.getDescription().isBlank()) {
			throw new PublishingFieldRequiredException("description");
		}

		Address address = accommodation.getAddress();
		if (address == null
			|| address.getCountry() == null
			|| address.getCity() == null
			|| address.getStreet() == null
			|| address.getPostalCode() == null
			|| address.getLatitude() == null
			|| address.getLongitude() == null) {
			throw new PublishingFieldRequiredException("addressInfo", "주소 정보(세부 항목 포함)가 누락되었습니다.");
		}

		if (accommodation.getBasePrice() == null || accommodation.getBasePrice() < 1) {
			throw new PublishingFieldRequiredException("basePrice", "기본 가격은 1원 이상이어야 합니다.");
		}
		if (accommodation.getType() == null) {
			throw new PublishingFieldRequiredException("type");
		}

		String timeZoneId = accommodation.getTimeZoneId();
		if (timeZoneId == null || timeZoneId.isBlank()) {
			throw new PublishingFieldRequiredException("timeZoneId");
		}
		if (!ZoneId.getAvailableZoneIds().contains(timeZoneId)) {
			throw new PublishingFieldRequiredException("timeZoneId", "유효한 IANA 시간대 식별자가 필요합니다.");
		}

		OccupancyPolicy policy = accommodation.getOccupancyPolicy();
		if (policy == null || policy.getMaxOccupancy() == null
			|| policy.getInfantOccupancy() == null
			|| policy.getPetOccupancy() == null) {
			throw new PublishingFieldRequiredException(
				"occupancyPolicyInfo", "수용 인원 정책(세부 항목 포함)이 누락되었습니다."
			);
		}

		if (accommodation.getCheckInTime() == null || accommodation.getCheckOutTime() == null) {
			throw new PublishingFieldRequiredException(
				"checkInTime/checkOutTime", "체크인/체크아웃 시간은 필수입니다."
			);
		}
		if (accommodation.getCheckOutTime().isAfter(accommodation.getCheckInTime())) {
			throw new PublishingFieldRequiredException(
				"checkInTime/checkOutTime", "체크아웃 시간은 체크인 시간보다 늦을 수 없습니다."
			);
		}

		long imageCount = accommodationImageRepository.countByAccommodationId(accommodation.getId());
		int minImageCount = 1;
		if (imageCount < minImageCount) {
			throw new InsufficientImageCountException(
				"이미지는 최소 " + minImageCount + "개 이상 등록해야 게시할 수 있습니다. 현재: " + imageCount + "개"
			);
		}
	}

	private void validateTurnoverTimes(Accommodation accommodation, AccommodationRequest.Update request) {
		if (request.checkInTime() == null && request.checkOutTime() == null) {
			return;
		}
		LocalTime checkInTime = request.checkInTime() != null
			? request.checkInTime() : accommodation.getCheckInTime();
		LocalTime checkOutTime = request.checkOutTime() != null
			? request.checkOutTime() : accommodation.getCheckOutTime();
		if (checkInTime != null && checkOutTime != null && checkOutTime.isAfter(checkInTime)) {
			throw new InvalidInputException("체크아웃 시간은 체크인 시간보다 늦을 수 없습니다.");
		}
	}

	private void validateTemporalSettingsChange(
		Accommodation accommodation,
		AccommodationRequest.Update request,
		ResolvedLocation resolvedLocation
	) {
		LocalTime nextCheckInTime = request.checkInTime() != null
			? request.checkInTime() : accommodation.getCheckInTime();
		LocalTime nextCheckOutTime = request.checkOutTime() != null
			? request.checkOutTime() : accommodation.getCheckOutTime();
		String nextTimeZoneId = resolvedLocation != null
			? resolvedLocation.timeZone().getId() : accommodation.getTimeZoneId();
		boolean temporalSettingsChanged = !Objects.equals(
			accommodation.getCheckInTime(), nextCheckInTime)
			|| !Objects.equals(accommodation.getCheckOutTime(), nextCheckOutTime)
			|| !Objects.equals(accommodation.getTimeZoneId(), nextTimeZoneId);
		if (temporalSettingsChanged
			&& reservationRepository.existsFutureInventoryReservation(
				accommodation.getId(), clock.instant())) {
			throw new InvalidInputException(
				"미래 유효 예약이 있는 숙소의 체크인 시간, 체크아웃 시간, 시간대는 변경할 수 없습니다."
			);
		}
	}

	private void saveValidAmenities(Map<String, Integer> amenityCountMap, Accommodation savedAccommodation) {
		if (amenityCountMap.isEmpty()) {
			return;
		}

		List<AccommodationAmenity> accommodationAmenityList = amenityCountMap.entrySet().stream()
			.map(entry -> AccommodationAmenity.createAccommodationAmenity(
				savedAccommodation, entry.getKey(), entry.getValue()))
			.toList();

		accommodationAmenityRepository.saveAll(accommodationAmenityList);
	}

	private Map<String, Integer> getAmenityCountMap(List<AmenityRequest.AmenityInfo> request) {
		if (request == null) {
			return null;
		}

		Map<String, Integer> amenityCountMap = request.stream()
			.filter(info -> info.count() > 0)
			.map(info -> new AbstractMap.SimpleEntry<>(
				info.name() == null ? null : info.name().toUpperCase(Locale.ROOT), info.count()))
			.collect(Collectors.toMap(
				Map.Entry::getKey,
				Map.Entry::getValue,
				Integer::sum
			));

		boolean hasInvalidCode = amenityCountMap.keySet().stream()
			.anyMatch(code -> !commonCodeService.isValidCode(CommonCodeGroups.AMENITY_TYPE, code));
		if (hasInvalidCode) {
			throw new InvalidAccommodationAmenityException();
		}

		return amenityCountMap;
	}

	private void validateAccommodationType(String type) {
		if (type == null) {
			return;
		}
		if (!commonCodeService.isValidCode(
			CommonCodeGroups.ACCOMMODATION_TYPE, type.toUpperCase(Locale.ROOT))) {
			throw new InvalidAccommodationTypeException();
		}
	}

	private void updateOccupancyPolicy(
		Accommodation accommodation,
		PolicyRequest.OccupancyPolicyInfo occupancyPolicyInfo
	) {
		if (occupancyPolicyInfo == null) {
			return;
		}

		OccupancyPolicy currentPolicy = accommodation.getOccupancyPolicy();
		if (currentPolicy == null) {
			OccupancyPolicy newPolicy = OccupancyPolicy.createOccupancyPolicy(occupancyPolicyInfo);
			OccupancyPolicy savedPolicy = occupancyPolicyRepository.save(newPolicy);
			accommodation.updateOccupancyPolicy(savedPolicy);
		}
	}

	private ResolvedLocation resolveLocation(
		Accommodation accommodation,
		AddressRequest.AddressInfo addressInfo
	) {
		if (addressInfo == null) {
			return null;
		}

		Address currentAddress = accommodation.getAddress();
		if (currentAddress != null
			&& !currentAddress.isChanged(addressInfo)
			&& accommodation.getTimeZoneId() != null) {
			return null;
		}

		String addressStr = Address.buildAddressStringForGeocoding(addressInfo);
		GeocodeResult geocodeResult = geocodingService.getCoordinates(addressStr);
		if (geocodeResult == null
			|| !geocodeResult.success()
			|| geocodeResult.latitude() == null
			|| geocodeResult.longitude() == null) {
			throw new AccommodationLocationResolutionException();
		}

		ZoneId timeZone = timeZoneResolver.resolve(geocodeResult.latitude(), geocodeResult.longitude())
			.orElseThrow(AccommodationLocationResolutionException::new);
		return new ResolvedLocation(Address.createAddress(addressInfo, geocodeResult), timeZone);
	}

	private void updateLocation(Accommodation accommodation, ResolvedLocation resolvedLocation) {
		if (resolvedLocation == null) {
			return;
		}

		Address savedAddress = addressRepository.save(resolvedLocation.address());
		accommodation.updateLocation(savedAddress, resolvedLocation.timeZone());
	}

	private record ResolvedLocation(Address address, ZoneId timeZone) {
	}

	private void updateAmenities(Accommodation accommodation, Map<String, Integer> amenityCountMap) {
		if (amenityCountMap == null) {
			return;
		}

		accommodationAmenityRepository.deleteByAccommodationIdInBulk(accommodation.getId());

		if (!amenityCountMap.isEmpty()) {
			saveValidAmenities(amenityCountMap, accommodation);
		}
	}

	private Accommodation findWithDetailsExceptHostAndDeletedById(Long accommodationId, Long memberId) {
		return accommodationRepository.findWithDetailsExceptHostAndDeletedById(accommodationId, memberId)
			.orElseThrow(AccommodationNotFoundException::new);
	}

	private Accommodation findByIdAndMemberIdAndStatusNot(Long accommodationId, Long memberId) {
		return accommodationRepository.findByIdAndMemberIdAndStatusNotForUpdate(
			accommodationId, memberId, AccommodationStatus.DELETED
		).orElseThrow(AccommodationNotFoundException::new);
	}

	private Accommodation findByIdAndMemberIdExceptDeleted(Long accommodationId, Long memberId) {
		return accommodationRepository.findByIdAndMemberIdAndStatusNot(
			accommodationId, memberId, AccommodationStatus.DELETED
		).orElseThrow(AccommodationNotFoundException::new);
	}
}
