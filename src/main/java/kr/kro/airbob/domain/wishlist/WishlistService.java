package kr.kro.airbob.domain.wishlist;

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
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.image.AccommodationImage;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.wishlist.dto.WishlistRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
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
	private final AccommodationReviewSummaryRepository summaryRepository;
	private final WishlistAccommodationRepository wishlistAccommodationRepository;

	private final CursorPageInfoCreator cursorPageInfoCreator;

	@Transactional
	public WishlistResponse.CreateResponse createWishlist(WishlistRequest.createRequest request) {

		Long memberId = getMemberId();
		final Member member = findMemberById(memberId);

		Wishlist wishlist = Wishlist.builder()
			.name(request.name())
			.member(member)
			.build();

		Wishlist savedWishlist = wishlistRepository.save(wishlist);
		return new WishlistResponse.CreateResponse(savedWishlist.getId());
	}

	@Transactional
	public WishlistResponse.UpdateResponse updateWishlist(Long wishlistId, WishlistRequest.updateRequest request) {

		Wishlist wishlist = findWishlistById(wishlistId);

		wishlist.updateName(request.name());

		return new WishlistResponse.UpdateResponse(wishlist.getId());
	}

	@Transactional
	public void deleteWishlist(Long wishlistId) {
		// 위시리스트 존재, 작성자 id 검증을 위한 조회
		Wishlist wishlist = findWishlistById(wishlistId);

		// 위시리스트에 속한 숙소 삭제
		wishlistAccommodationRepository.deleteAllByWishlistId(wishlist.getId());
		wishlistRepository.delete(wishlist);
	}

	@Transactional(readOnly = true)
	public WishlistResponse.WishlistInfos findWishlists(CursorRequest.CursorPageRequest request) {

		Long lastId = request.lastId();
		LocalDateTime lastCreatedAt = request.lastCreatedAt();

		Long memberId = getMemberId();

		Slice<Wishlist> wishlistSlice = wishlistRepository.findByMemberIdWithCursor(
			memberId,
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
	public WishlistResponse.CreateWishlistAccommodationResponse createWishlistAccommodation(Long wishlistId,
		WishlistRequest.CreateWishlistAccommodationRequest request) {

		Accommodation accommodation = findAccommodationById(request.accommodationId());
		validateWishlistAccommodationDuplicate(wishlistId, accommodation.getId());

		Wishlist wishlist = findWishlistById(wishlistId);

		WishlistAccommodation wishlistAccommodation = WishlistAccommodation.builder()
			.wishlist(wishlist)
			.accommodation(accommodation)
			.build();

		WishlistAccommodation savedWishlistAccommodation
			= wishlistAccommodationRepository.save(wishlistAccommodation);

		return new WishlistResponse.CreateWishlistAccommodationResponse(savedWishlistAccommodation.getId());
	}

	@Transactional
	public WishlistResponse.UpdateWishlistAccommodationResponse updateWishlistAccommodation(
		Long wishlistAccommodationId, WishlistRequest.UpdateWishlistAccommodationRequest request) {

		WishlistAccommodation wishlistAccommodation = findWishlistAccommodation(wishlistAccommodationId);
		wishlistAccommodation.updateMemo(request.memo());

		return new WishlistResponse.UpdateWishlistAccommodationResponse(wishlistAccommodation.getId());
	}

	@Transactional
	public void deleteWishlistAccommodation(Long wishlistAccommodationId) {

		WishlistAccommodation wishlistAccommodation = findWishlistAccommodation(wishlistAccommodationId);

		wishlistAccommodationRepository.delete(wishlistAccommodation);
	}

	@Transactional(readOnly = true)
	public WishlistResponse.WishlistAccommodationInfos findWishlistAccommodations(Long wishlistId,
		CursorRequest.CursorPageRequest request) {

		Long lastId = request.lastId();
		LocalDateTime lastCreatedAt = request.lastCreatedAt();

		Slice<WishlistAccommodation> wishlistAccommodationSlice = wishlistAccommodationRepository.findByWishlistIdWithCursor(
			wishlistId,
			lastId,
			lastCreatedAt,
			PageRequest.of(0, request.size())
		);

		List<WishlistAccommodation> wishlistAccommodations = wishlistAccommodationSlice.getContent();

		if (wishlistAccommodations.isEmpty()) {
			CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
				wishlistAccommodations,
				wishlistAccommodationSlice.hasNext(),
				WishlistAccommodation::getId,
				WishlistAccommodation::getCreatedAt
			);
			return new WishlistResponse.WishlistAccommodationInfos(List.of(), pageInfo);
		}

		List<Long> accommodationIds = wishlistAccommodations.stream().map(wa -> wa.getAccommodation().getId()).toList();

		// 숙소 이미지
		Map<Long, List<String>> imageUrlsMap = getAccommodationImageUrls(accommodationIds);

		// 숙소 편의시설
		Map<Long, List<AccommodationResponse.AmenityInfoResponse>> amenitiesMap = getAccommodationAmenities(accommodationIds);

		// 숙소 리뷰 평점
		Map<Long, BigDecimal> ratingMap = getAccommodationRatings(accommodationIds);

		List<WishlistResponse.WishlistAccommodationInfo> wishlistAccommodationInfos = wishlistAccommodations.stream()
			.map(wa -> {
				Accommodation accommodation = wa.getAccommodation();
				Long accommodationId = accommodation.getId();

				AccommodationResponse.WishlistAccommodationInfo accommodationInfo =
					new AccommodationResponse.WishlistAccommodationInfo(
						accommodationId,
						accommodation.getName(),
						imageUrlsMap.getOrDefault(accommodationId, List.of()),
						amenitiesMap.getOrDefault(accommodationId, List.of()),
						ratingMap.get(accommodationId)
					);

				return new WishlistResponse.WishlistAccommodationInfo(
					wa.getId(),
					wa.getMemo(),
					accommodationInfo
				);
			}).toList();

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			wishlistAccommodations,
			wishlistAccommodationSlice.hasNext(),
			WishlistAccommodation::getId,
			WishlistAccommodation::getCreatedAt
		);

		return new WishlistResponse.WishlistAccommodationInfos(wishlistAccommodationInfos, pageInfo);
	}

	private Map<Long, List<String>> getAccommodationImageUrls(List<Long> accommodationIds) {
		List<AccommodationImage> results = accommodationRepository
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

	private Map<Long, BigDecimal> getAccommodationRatings(List<Long> accommodationIds) {
		List<AccommodationReviewSummary> results
			= summaryRepository.findByAccommodationIdIn(accommodationIds);

		return results.stream()
			.collect(Collectors.toMap(
				AccommodationReviewSummary::getAccommodationId,
				AccommodationReviewSummary::getAverageRating
			));
	}

	private Wishlist findWishlistById(Long wishlistId) {
		return wishlistRepository.findById(wishlistId).orElseThrow(WishlistNotFoundException::new);
	}

	private Member findMemberById(Long loggedInMemberId) {
		return memberRepository.findById(loggedInMemberId).orElseThrow(MemberNotFoundException::new);
	}

	private Accommodation findAccommodationById(Long accommodationId) {
		return accommodationRepository.findById(accommodationId).orElseThrow(AccommodationNotFoundException::new);
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

	private Long getMemberId() {
		return UserContext.get().id();
	}
}
