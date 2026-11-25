package kr.kro.airbob.core.domain.accommodation;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.AccommodationLocationResolver.ResolvedLocation;
import kr.kro.airbob.core.domain.accommodation.port.AccommodationReservationReader;
import kr.kro.airbob.core.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.core.domain.commoncode.CommonCodeGroups;
import kr.kro.airbob.core.domain.commoncode.CommonCodeReader;
import kr.kro.airbob.core.domain.image.InsufficientImageCountException;
import kr.kro.airbob.core.support.error.InvalidInputException;
import lombok.RequiredArgsConstructor;

/** 숙소 수정과 게시의 입력·상태 검증. */
@Component
@RequiredArgsConstructor
public class AccommodationValidator {

	private final CommonCodeReader commonCodeReader;
	private final AccommodationReservationReader reservationReader;
	private final AccommodationImageRepository accommodationImageRepository;
	private final Clock clock;

	/** 숙소 유형이 활성 ACCOMMODATION_TYPE 코드인지. null이면 검사하지 않는다(유지). */
	public void validateType(String type) {
		if (type == null) {
			return;
		}
		if (!commonCodeReader.isValidCode(
			CommonCodeGroups.ACCOMMODATION_TYPE, type.toUpperCase(Locale.ROOT))) {
			throw new InvalidAccommodationTypeException();
		}
	}

	/** 바뀐 뒤의 체크아웃 시간이 체크인 시간보다 늦지 않은지. */
	public void validateTurnoverTimes(Accommodation accommodation, AccommodationPatch patch) {
		if (patch.checkInTime() == null && patch.checkOutTime() == null) {
			return;
		}
		LocalTime checkInTime = patch.checkInTime() != null
			? patch.checkInTime() : accommodation.getCheckInTime();
		LocalTime checkOutTime = patch.checkOutTime() != null
			? patch.checkOutTime() : accommodation.getCheckOutTime();
		if (checkInTime != null && checkOutTime != null && checkOutTime.isAfter(checkInTime)) {
			throw new InvalidInputException("체크아웃 시간은 체크인 시간보다 늦을 수 없습니다.");
		}
	}

	/**
	 * 편의시설 코드별 개수. 0개 항목은 빼고 코드를 대문자로 맞춰 개수를 합친다. 목록이 null이면 null(유지).
	 * 알 수 없는 코드면 InvalidAccommodationAmenityException.
	 */
	public Map<String, Integer> countAmenities(List<NewAmenity> amenities) {
		if (amenities == null) {
			return null;
		}

		Map<String, Integer> amenityCountMap = amenities.stream()
			.filter(info -> info.count() > 0)
			.map(info -> new AbstractMap.SimpleEntry<>(
				info.name() == null ? null : info.name().toUpperCase(Locale.ROOT), info.count()))
			.collect(Collectors.toMap(
				Map.Entry::getKey,
				Map.Entry::getValue,
				this::sumAmenityCounts));

		boolean hasInvalidCode = amenityCountMap.keySet().stream()
			.anyMatch(code -> !commonCodeReader.isValidCode(CommonCodeGroups.AMENITY_TYPE, code));
		if (hasInvalidCode) {
			throw new InvalidAccommodationAmenityException();
		}

		return amenityCountMap;
	}

	/** 미래 재고 예약이 있는 숙소의 체크인·체크아웃 시간과 시간대는 바꿀 수 없다. */
	public void validateTemporalSettingsChange(
		Accommodation accommodation,
		AccommodationPatch patch,
		ResolvedLocation resolvedLocation) {
		LocalTime nextCheckInTime = patch.checkInTime() != null
			? patch.checkInTime() : accommodation.getCheckInTime();
		LocalTime nextCheckOutTime = patch.checkOutTime() != null
			? patch.checkOutTime() : accommodation.getCheckOutTime();
		String nextTimeZoneId = resolvedLocation != null
			? resolvedLocation.timeZone().getId() : accommodation.getTimeZoneId();
		boolean temporalSettingsChanged = !Objects.equals(
			accommodation.getCheckInTime(), nextCheckInTime)
			|| !Objects.equals(accommodation.getCheckOutTime(), nextCheckOutTime)
			|| !Objects.equals(accommodation.getTimeZoneId(), nextTimeZoneId);
		if (temporalSettingsChanged
			&& reservationReader.existsFutureInventoryReservation(
				accommodation.getId(), clock.instant())) {
			throw new InvalidInputException(
				"미래 유효 예약이 있는 숙소의 체크인 시간, 체크아웃 시간, 시간대는 변경할 수 없습니다.");
		}
	}

	/** 게시에 필요한 항목(이름, 설명, 주소·좌표, 가격, 유형, 시간대, 인원 정책, 체크인·체크아웃, 이미지 1개 이상)이 모두 있는지. */
	public void validateForPublishing(Accommodation accommodation) {
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
				"occupancyPolicyInfo", "수용 인원 정책(세부 항목 포함)이 누락되었습니다.");
		}

		if (accommodation.getCheckInTime() == null || accommodation.getCheckOutTime() == null) {
			throw new PublishingFieldRequiredException(
				"checkInTime/checkOutTime", "체크인/체크아웃 시간은 필수입니다.");
		}
		if (accommodation.getCheckOutTime().isAfter(accommodation.getCheckInTime())) {
			throw new PublishingFieldRequiredException(
				"checkInTime/checkOutTime", "체크아웃 시간은 체크인 시간보다 늦을 수 없습니다.");
		}

		long imageCount = accommodationImageRepository.countByAccommodationId(accommodation.getId());
		int minImageCount = 1;
		if (imageCount < minImageCount) {
			throw new InsufficientImageCountException(
				"이미지는 최소 " + minImageCount + "개 이상 등록해야 게시할 수 있습니다. 현재: " + imageCount + "개");
		}
	}

	private int sumAmenityCounts(int first, int second) {
		try {
			return Math.addExact(first, second);
		} catch (ArithmeticException exception) {
			throw new InvalidInputException("같은 편의시설의 개수 합계는 2147483647 이하여야 합니다.");
		}
	}
}
