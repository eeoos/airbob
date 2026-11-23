package kr.kro.airbob.core.domain.accommodation;

import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.core.domain.accommodation.AccommodationLocationResolver.ResolvedLocation;
import kr.kro.airbob.core.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.core.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.core.domain.accommodation.port.AccommodationInventorySeeder;
import kr.kro.airbob.core.domain.accommodation.port.AccommodationSearchRefreshRequester;
import kr.kro.airbob.core.domain.member.Member;
import kr.kro.airbob.core.domain.member.MemberReader;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccommodationCommandService {

	private final AccommodationReader accommodationReader;
	private final AccommodationWriter accommodationWriter;
	private final AccommodationValidator accommodationValidator;
	private final AccommodationLocationResolver locationResolver;
	private final MemberReader memberReader;
	private final AccommodationSearchRefreshRequester searchRefreshPublisher;
	private final AccommodationDetailCacheInvalidationPublisher cacheInvalidationPublisher;
	private final AccommodationInventorySeeder inventorySeedService;

	/** 호스트의 빈 숙소를 만들고 숙소 ID를 돌려준다. */
	@Transactional
	public Long createAccommodation(Long memberId) {
		Member member = memberReader.readActive(memberId);

		return accommodationWriter.create(member).getId();
	}

	@Transactional
	public void updateAccommodation(Long accommodationId, AccommodationUpdate update, Long memberId) {
		Accommodation accommodation = accommodationReader.readOwnedForUpdate(accommodationId, memberId);
		String previousTimeZoneId = accommodation.getTimeZoneId();
		AccommodationPatch patch = update.patch();

		accommodationValidator.validateType(patch.type());
		accommodationValidator.validateTurnoverTimes(accommodation, patch);
		Map<String, Integer> amenityCountMap = accommodationValidator.countAmenities(update.amenities());
		ResolvedLocation resolvedLocation = locationResolver.resolve(accommodation, update.address());
		accommodationValidator.validateTemporalSettingsChange(accommodation, patch, resolvedLocation);
		accommodation.updateAccommodation(patch);
		accommodationWriter.updateLocation(accommodation, resolvedLocation);
		accommodationWriter.updateOccupancyPolicy(accommodation, update.occupancyPolicy());
		accommodationWriter.replaceAmenities(accommodation, amenityCountMap);
		if (accommodation.getStatus() == AccommodationStatus.PUBLISHED
			&& !Objects.equals(previousTimeZoneId, accommodation.getTimeZoneId())) {
			inventorySeedService.seedCurrentHorizon(accommodation);
		}

		accommodationWriter.recordHistory(accommodation, ChangeType.UPDATE, "숙소 정보 수정");

		if (accommodation.getStatus() == AccommodationStatus.PUBLISHED) {
			searchRefreshPublisher.requestRefresh(accommodation.getAccommodationUid());
		}

		cacheInvalidationPublisher.publish(
			accommodationId, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
	}

	@Transactional
	public void deleteAccommodation(Long accommodationId, Long memberId) {
		Accommodation accommodation = accommodationReader.readOwnedForUpdate(accommodationId, memberId);

		accommodationWriter.delete(accommodation);

		searchRefreshPublisher.requestRefresh(accommodation.getAccommodationUid());
		cacheInvalidationPublisher.publish(
			accommodationId, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
	}

	@Transactional
	public void publishAccommodation(Long accommodationId, Long memberId) {
		Accommodation accommodation = accommodationReader.readOwnedForUpdate(accommodationId, memberId);

		accommodationValidator.validateForPublishing(accommodation);
		inventorySeedService.seedCurrentHorizon(accommodation);
		accommodationWriter.publish(accommodation);

		searchRefreshPublisher.requestRefresh(accommodation.getAccommodationUid());
		cacheInvalidationPublisher.publish(
			accommodationId, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
	}

	@Transactional
	public void unpublishAccommodation(Long accommodationId, Long memberId) {
		Accommodation accommodation = accommodationReader.readOwnedForUpdate(accommodationId, memberId);

		if (accommodation.getStatus() != AccommodationStatus.PUBLISHED) {
			throw new AccommodationStateException();
		}

		accommodationWriter.unpublish(accommodation);

		searchRefreshPublisher.requestRefresh(accommodation.getAccommodationUid());
		cacheInvalidationPublisher.publish(
			accommodationId, AccommodationDetailCacheInvalidationReason.ACCOMMODATION);
	}
}
