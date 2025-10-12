package kr.kro.airbob.domain.wishlist.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.image.AccommodationImage;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse;
import kr.kro.airbob.domain.wishlist.dto.WishlistRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.entity.Wishlist;
import kr.kro.airbob.domain.wishlist.entity.WishlistAccommodation;
import kr.kro.airbob.domain.wishlist.entity.WishlistStatus;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccommodationDuplicateException;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccommodationNotFoundException;
import kr.kro.airbob.domain.wishlist.exception.WishlistNotFoundException;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.domain.wishlist.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class WishlistService {

	private final MemberRepository memberRepository;
	private final WishlistRepository wishlistRepository;
	private final AccommodationRepository accommodationRepository;
	private final AccommodationAmenityRepository amenityRepository;
	private final AccommodationImageRepository accommodationImageRepository;
	private final WishlistAccommodationRepository wishlistAccommodationRepository;

	private final CursorPageInfoCreator cursorPageInfoCreator;

	@Transactional
	public WishlistResponse.Create createWishlist(WishlistRequest.Create request, Long memberId) {

		final Member member = findMemberById(memberId);

		Wishlist wishlist = Wishlist.builder()
			.name(request.name())
			.member(member)
			.build();

		Wishlist savedWishlist = wishlistRepository.save(wishlist);
		return new WishlistResponse.Create(savedWishlist.getId());
	}

	@Transactional
	public WishlistResponse.Update updateWishlist(Long wishlistId, WishlistRequest.Update request, Long memberId) {

		Wishlist wishlist = findWishlistByIdAndMemberId(wishlistId, memberId);

		wishlist.updateName(request.name());

		return new WishlistResponse.Update(wishlist.getId());
	}

	@Transactional
	public void deleteWishlist(Long wishlistId, Long memberId) {
		// 위시리스트 존재, 작성자 id 검증을 위한 조회
		Wishlist wishlist = findWishlistByIdAndMemberId(wishlistId, memberId);

		// 위시리스트에 속한 숙소 삭제
		wishlistAccommodationRepository.deleteAllByWishlistId(wishlist.getId());
		wishlist.delete();
	}

	@Transactional(readOnly = true)
	public WishlistResponse.WishlistInfos findWishlists(CursorRequest.CursorPageRequest request, Long memberId) {

		Long lastId = request.lastId();
		LocalDateTime lastCreatedAt = request.lastCreatedAt();


		Slice<Wishlist> wishlistSlice = wishlistRepository.findByMemberIdAndStatusWithCursor(
			memberId,
			WishlistStatus.ACTIVE,
			lastId,
			lastCreatedAt,
			PageRequest.of(0, request.size())
		);

		List<Long> wishlistIds = wishlistSlice.getContent().stream()
			.map(Wishlist::getId)
			.toList();

		// 위시리스트별 숙소 개수 조회
		Map<Long, Long> wishlistItemCounts = wishlistAccommodationRepository.countByWishlistIds(wishlistIds);

		// 위시리스트별 가장 최근에 추가된 숙소 썸네일 Url 조회
		Map<Long, String> thumbnailUrls = wishlistAccommodationRepository.findLatestThumbnailUrlsByWishlistIds(wishlistIds);

		List<WishlistResponse.WishlistInfo> wishlistInfos = wishlistSlice.getContent().stream()
			.map(wishlist ->
				new WishlistResponse.WishlistInfo(
					wishlist.getId(),
					wishlist.getName(),
					wishlist.getCreatedAt(),
					wishlistItemCounts.getOrDefault(wishlist.getId(), 0L),
					thumbnailUrls.get(wishlist.getId()) // nullable
				)).toList();

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			wishlistSlice.getContent(),
			wishlistSlice.hasNext(),
			Wishlist::getId,
			Wishlist::getCreatedAt
		);

		return new WishlistResponse.WishlistInfos(wishlistInfos, pageInfo);
	}

	@Transactional
	public WishlistAccommodationResponse.Create createWishlistAccommodation(Long wishlistId,
		WishlistAccommodationRequest.Create request, Long memberId) {
		Wishlist wishlist = findWishlistByIdAndMemberId(wishlistId, memberId);

		Accommodation accommodation = findAccommodationById(request.accommodationId());
		validateWishlistAccommodationDuplicate(wishlistId, accommodation.getId());


		WishlistAccommodation wishlistAccommodation = WishlistAccommodation.builder()
			.wishlist(wishlist)
			.accommodation(accommodation)
			.build();

		WishlistAccommodation savedWishlistAccommodation
			= wishlistAccommodationRepository.save(wishlistAccommodation);

		return new WishlistAccommodationResponse.Create(savedWishlistAccommodation.getId());
	}

	@Transactional
	public WishlistAccommodationResponse.Update updateWishlistAccommodation(
		Long wishlistAccommodationId, WishlistAccommodationRequest.Update request) {

		WishlistAccommodation wishlistAccommodation = findWishlistAccommodation(wishlistAccommodationId);
		wishlistAccommodation.updateMemo(request.memo());

		return new WishlistAccommodationResponse.Update(wishlistAccommodation.getId());
	}

	@Transactional
	public void deleteWishlistAccommodation(Long wishlistAccommodationId) {

		WishlistAccommodation wishlistAccommodation = findWishlistAccommodation(wishlistAccommodationId);

		wishlistAccommodationRepository.delete(wishlistAccommodation);
	}

	@Transactional(readOnly = true)
	public WishlistAccommodationResponse.WishlistAccommodationInfos findWishlistAccommodations(Long wishlistId,
		CursorRequest.CursorPageRequest request) {

		Slice<WishlistAccommodationResponse.WishlistAccommodationInfo> slice =
			wishlistAccommodationRepository.findAccommodationsInWishlist(
				wishlistId,
				request.lastId(),
				request.lastCreatedAt(),
				PageRequest.of(0, request.size())
			);

		List<WishlistAccommodationResponse.WishlistAccommodationInfo> infos = slice.getContent();

		if (infos.isEmpty()) {
			CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
				infos,
				slice.hasNext(),
				WishlistAccommodationResponse.WishlistAccommodationInfo::wishlistAccommodationId,
				WishlistAccommodationResponse.WishlistAccommodationInfo::createdAt
			);
			return new WishlistAccommodationResponse.WishlistAccommodationInfos(List.of(), pageInfo);
		}

		List<Long> accommodationIds = infos.stream()
			.map(WishlistAccommodationResponse.WishlistAccommodationInfo::accommodationId)
			.toList();

		Map<Long, List<String>> imageUrlsMap = getAccommodationImageUrls(accommodationIds);
		Map<Long, List<AccommodationResponse.AmenityInfoResponse>> amenitiesMap = getAccommodationAmenities(accommodationIds);

		infos.forEach(info -> {
			info.imageUrls().addAll(imageUrlsMap.getOrDefault(info.accommodationId(), List.of()));
			info.amenities().addAll(amenitiesMap.getOrDefault(info.accommodationId(), List.of()));
		});

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			infos,
			slice.hasNext(),
			WishlistAccommodationResponse.WishlistAccommodationInfo::wishlistAccommodationId,
			WishlistAccommodationResponse.WishlistAccommodationInfo::createdAt
		);

		return new WishlistAccommodationResponse.WishlistAccommodationInfos(infos, pageInfo);
	}

	private Map<Long, List<String>> getAccommodationImageUrls(List<Long> accommodationIds) {
		List<AccommodationImage> results = accommodationImageRepository
			.findAccommodationImagesByAccommodationIds(accommodationIds);

		return results .stream()
			.collect(Collectors.groupingBy(
				ai -> ai.getAccommodation().getId(),
				Collectors.mapping(
					AccommodationImage::getImageUrl,
					Collectors.toList()
				)
			));
	}

	private Map<Long, List<AccommodationResponse.AmenityInfoResponse>> getAccommodationAmenities(
		List<Long> accommodationIds) {

		List<AccommodationAmenity> results
			= amenityRepository.findAccommodationAmenitiesByAccommodationIds(accommodationIds);

		return results.stream()
			.collect(Collectors.groupingBy(
				aa -> aa.getAccommodation().getId(),
				Collectors.mapping(
					result -> new AccommodationResponse.AmenityInfoResponse(
						result.getAmenity().getName(),
						result.getCount()
					),
					Collectors.toList()
				)
			));
	}

	private Wishlist findWishlistById(Long wishlistId) {
		return wishlistRepository.findByIdAndStatus(wishlistId, WishlistStatus.ACTIVE).orElseThrow(WishlistNotFoundException::new);
	}

	private Member findMemberById(Long loggedInMemberId) {
		return memberRepository.findByIdAndStatus(loggedInMemberId, MemberStatus.ACTIVE).orElseThrow(MemberNotFoundException::new);
	}

	private Accommodation findAccommodationById(Long accommodationId) {
		return accommodationRepository.findByIdAndStatus(accommodationId, AccommodationStatus.PUBLISHED).orElseThrow(AccommodationNotFoundException::new);
	}

	private WishlistAccommodation findWishlistAccommodation(Long wishlistAccommodationId){
		return wishlistAccommodationRepository.findById(wishlistAccommodationId)
			.orElseThrow(WishlistAccommodationNotFoundException::new);
	}

	private void validateWishlistAccommodationDuplicate(Long wishlistId, Long accommodationId) {
		if (wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(wishlistId, accommodationId)) {
			throw new WishlistAccommodationDuplicateException();
		}
	}

	private Wishlist findWishlistByIdAndMemberId(Long wishlistId, Long memberId) {
		Wishlist wishlist = wishlistRepository.findByIdAndStatus(wishlistId, WishlistStatus.ACTIVE)
			.orElseThrow(WishlistNotFoundException::new);

		if (!wishlist.getMember().getId().equals(memberId)) {
			throw new WishlistAccessDeniedException();
		}
		return wishlist;
	}
}
