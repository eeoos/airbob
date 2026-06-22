package kr.kro.airbob.domain.wishlist.service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse;
import kr.kro.airbob.domain.wishlist.dto.WishlistRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.entity.Wishlist;
import kr.kro.airbob.domain.wishlist.entity.WishlistAccommodation;
import kr.kro.airbob.domain.wishlist.entity.WishlistStatus;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccommodationAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccommodationDuplicateException;
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
	public WishlistResponse.WishlistInfos findWishlists(
		CursorRequest.CursorPageRequest request,
		Long memberId,
		Long accommodationId) {

		Long lastId = request.lastId();
		LocalDateTime lastCreatedAt = request.lastCreatedAt();


		Slice<Wishlist> wishlistSlice = wishlistRepository.findByMemberIdAndStatusWithCursor(
			memberId,
			WishlistStatus.ACTIVE,
			lastId,
			lastCreatedAt,
			PageRequest.of(0, request.size())
		);

		List<Wishlist> wishlists = wishlistSlice.getContent();
		if (wishlists.isEmpty()) {
			return WishlistResponse.WishlistInfos.builder()
				.wishlists(Collections.emptyList())
				.pageInfo(cursorPageInfoCreator.createPageInfo(
					Collections.emptyList(),
					false,
					Wishlist::getId,
					Wishlist::getCreatedAt
				))
				.build();
		}

		List<Long> wishlistIds = wishlists.stream()
			.map(Wishlist::getId)
			.toList();

		// 반정규화: 대표(최신) 숙소 id 묶음 → 썸네일 URL 배치 조회 (기존 ROW_NUMBER 윈도우 함수 제거)
		List<Long> representativeIds = wishlists.stream()
			.map(Wishlist::getRepresentativeAccommodationId)
			.filter(id -> id != null)
			.distinct()
			.toList();

		Map<Long, String> thumbnailUrls = representativeIds.isEmpty()
			? Collections.emptyMap()
			: accommodationRepository.findThumbnailUrlsByIds(representativeIds).stream()
				.filter(p -> p.getThumbnailUrl() != null)
				.collect(Collectors.toMap(
					AccommodationRepository.ThumbnailUrlProjection::getId,
					AccommodationRepository.ThumbnailUrlProjection::getThumbnailUrl
				));

		List<WishlistResponse.WishlistInfo> wishlistInfos;

		if (accommodationId != null) {
			Map<Long, Long> accommodationStatusMap = wishlistAccommodationRepository
				.findWishlistAccIdsMapByWishlistIdsAndAccId(wishlistIds, accommodationId);

			wishlistInfos = wishlists.stream()
				.map(wishlist -> {
					Long currentWishlistId = wishlist.getId();
					Long wishlistAccommodationId = accommodationStatusMap.get(currentWishlistId);
					Boolean isContained = (wishlistAccommodationId != null);

					return new WishlistResponse.WishlistInfo(
						currentWishlistId,
						wishlist.getName(),
						wishlist.getCreatedAt(),
						wishlist.getAccommodationCount().longValue(),
						thumbnailUrls.get(wishlist.getRepresentativeAccommodationId()),
						isContained,
						wishlistAccommodationId
					);
				}).toList();
		} else {
			wishlistInfos = wishlists.stream()
				.map(wishlist ->
					new WishlistResponse.WishlistInfo(
						wishlist.getId(),
						wishlist.getName(),
						wishlist.getCreatedAt(),
						wishlist.getAccommodationCount().longValue(),
						thumbnailUrls.get(wishlist.getRepresentativeAccommodationId()),
						null,
						null
					)).toList();
		}

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			wishlistSlice.getContent(),
			wishlistSlice.hasNext(),
			Wishlist::getId,
			Wishlist::getCreatedAt
		);

		return WishlistResponse.WishlistInfos.builder()
			.wishlists(wishlistInfos)
			.pageInfo(pageInfo)
			.build();
	}

	@Transactional
	public WishlistAccommodationResponse.Create createWishlistAccommodation(Long wishlistId,
		WishlistAccommodationRequest.Create request, Long memberId) {
		Wishlist wishlist = findWishlistByIdAndMemberId(wishlistId, memberId);

		Accommodation accommodation = findAccommodationByIdAndStatus(request.accommodationId());
		validateWishlistAccommodationDuplicate(wishlistId, accommodation.getId());


		WishlistAccommodation wishlistAccommodation = WishlistAccommodation.builder()
			.wishlist(wishlist)
			.accommodation(accommodation)
			.build();

		WishlistAccommodation savedWishlistAccommodation
			= wishlistAccommodationRepository.save(wishlistAccommodation);

		// 반정규화: 개수 +1, 대표를 방금 추가한 숙소(=최신)로 설정
		wishlistRepository.incrementCountAndSetRepresentative(wishlist.getId(), accommodation.getId());

		return new WishlistAccommodationResponse.Create(savedWishlistAccommodation.getId());
	}

	@Transactional
	public WishlistAccommodationResponse.Update updateWishlistAccommodation(
		Long wishlistAccommodationId, WishlistAccommodationRequest.Update request, Long memberId) {

		WishlistAccommodation wishlistAccommodation = findWishlistAccommodationForMember(wishlistAccommodationId, memberId);
		wishlistAccommodation.updateMemo(request.memo());

		return new WishlistAccommodationResponse.Update(wishlistAccommodation.getId());
	}

	@Transactional
	public void deleteWishlistAccommodation(Long wishlistAccommodationId, Long memberId) {

		WishlistAccommodation wishlistAccommodation = findWishlistAccommodationForMember(wishlistAccommodationId, memberId);

		Long wishlistId = wishlistAccommodation.getWishlist().getId();
		Long removedAccommodationId = wishlistAccommodation.getAccommodation().getId();
		Long currentRepresentative = wishlistAccommodation.getWishlist().getRepresentativeAccommodationId();

		wishlistAccommodationRepository.delete(wishlistAccommodation);
		wishlistAccommodationRepository.flush(); // 대표 재선정 조회가 삭제 반영 후 실행되도록

		wishlistRepository.decrementCount(wishlistId);

		// 삭제된 숙소가 대표였을 때만 다음 최신 1건으로 재선정(없으면 null)
		if (removedAccommodationId.equals(currentRepresentative)) {
			Long newRepresentative = wishlistAccommodationRepository.findLatestAccommodationId(wishlistId);
			wishlistRepository.updateRepresentative(wishlistId, newRepresentative);
		}
	}

	@Transactional(readOnly = true)
	public WishlistAccommodationResponse.WishlistAccommodationInfos findWishlistAccommodations(Long wishlistId,
		CursorRequest.CursorPageRequest request, Long memberId) {

		findWishlistByIdAndMemberId(wishlistId, memberId);

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

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			infos,
			slice.hasNext(),
			WishlistAccommodationResponse.WishlistAccommodationInfo::wishlistAccommodationId,
			WishlistAccommodationResponse.WishlistAccommodationInfo::createdAt
		);

		return new WishlistAccommodationResponse.WishlistAccommodationInfos(infos, pageInfo);
	}

	private Member findMemberById(Long loggedInMemberId) {
		return memberRepository.findByIdAndStatus(loggedInMemberId, MemberStatus.ACTIVE).orElseThrow(MemberNotFoundException::new);
	}

	private Accommodation findAccommodationByIdAndStatus(Long accommodationId) {
		return accommodationRepository.findByIdAndStatus(accommodationId, AccommodationStatus.PUBLISHED).orElseThrow(AccommodationNotFoundException::new);
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

	private WishlistAccommodation findWishlistAccommodationForMember(Long wishlistAccommodationId, Long memberId) {
		return wishlistAccommodationRepository.findByIdAndWishlistMemberId(wishlistAccommodationId, memberId)
			.orElseThrow(WishlistAccommodationAccessDeniedException::new);
	}
}
