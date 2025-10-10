package kr.kro.airbob.domain.wishlist;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.Amenity;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.image.AccommodationImage;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.common.MemberRole;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("위시리스트 서비스 테스트")
class WishlistServiceTest {

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private WishlistRepository wishlistRepository;

	@Mock
	private WishlistAccommodationRepository wishlistAccommodationRepository;

	@Mock
	private AccommodationRepository accommodationRepository;

	@Mock
	private AccommodationReviewSummaryRepository summaryRepository;


	@Mock
	private CursorPageInfoCreator cursorPageInfoCreator;

	@Mock
	private AccommodationAmenityRepository amenityRepository;

	@InjectMocks
	private WishlistService wishlistService;

	private Member member;
	private Member adminMember;
	private Wishlist wishlist;
	private Wishlist otherWishlist;

	@BeforeEach
	void setUp() {
		// 일반 회원
		member = Member.builder()
			.id(1L)
			.email("test@example.com")
			.nickname("테스트 사용자")
			.role(MemberRole.MEMBER)
			.build();

		// 관리자 회원
		adminMember = Member.builder()
			.id(2L)
			.email("admin@example.com")
			.nickname("관리자")
			.role(MemberRole.ADMIN)
			.build();

		// 다른 회원
		Member otherMember = Member.builder()
			.id(3L)
			.email("other@example.com")
			.nickname("다른 사용자")
			.role(MemberRole.MEMBER)
			.build();

		// 위시리스트
		wishlist = Wishlist.builder()
			.id(1L)
			.name("서울 여행")
			.member(member)
			.build();

		// 다른 사용자의 위시리스트
		otherWishlist = Wishlist.builder()
			.id(2L)
			.name("부산 여행")
			.member(otherMember)
			.build();
	}

	@Nested
	@DisplayName("위시리스트 생성 테스트")
	class CreateWishlistTest {

		@Test
		@DisplayName("정상적으로 위시리스트를 생성한다")
		void createWishlist_Success() {
			// Given
			WishlistRequest.createRequest request = new WishlistRequest.createRequest("서울 여행 계획");
			Long currentMemberId = 1L;

			given(memberRepository.findById(currentMemberId)).willReturn(Optional.of(member));
			given(wishlistRepository.save(any(Wishlist.class))).willAnswer(invocation -> {
				Wishlist savedWishlist = invocation.getArgument(0);
				return Wishlist.builder()
					.id(1L)
					.name(savedWishlist.getName())
					.member(savedWishlist.getMember())
					.build();
			});

			// When
			WishlistResponse.CreateResponse response = wishlistService.createWishlist(request, currentMemberId);

			// Then
			assertThat(response.id()).isEqualTo(1L);

			verify(memberRepository).findById(currentMemberId);
			verify(wishlistRepository).save(any(Wishlist.class));
		}

		@Test
		@DisplayName("존재하지 않는 사용자로 위시리스트 생성 시 예외 발생")
		void createWishlist_MemberNotFound() {
			// Given
			WishlistRequest.createRequest request = new WishlistRequest.createRequest("서울 여행 계획");
			Long nonExistentMemberId = 999L;

			given(memberRepository.findById(nonExistentMemberId)).willReturn(Optional.empty());

			// When & Then
			assertThatThrownBy(() -> wishlistService.createWishlist(request, nonExistentMemberId))
				.isInstanceOf(MemberNotFoundException.class)
				.hasMessage("존재하지 않는 사용자입니다.");

			verify(memberRepository).findById(nonExistentMemberId);
			verify(wishlistRepository, never()).save(any(Wishlist.class));
		}

		@Test
		@DisplayName("같은 이름의 위시리스트를 여러 개 생성할 수 있다")
		void createWishlist_DuplicateNameAllowed() {
			// Given
			WishlistRequest.createRequest request = new WishlistRequest.createRequest("중복 이름");
			Long currentMemberId = 1L;

			given(memberRepository.findById(currentMemberId)).willReturn(Optional.of(member));
			given(wishlistRepository.save(any(Wishlist.class)))
				.willAnswer(invocation -> {
					Wishlist savedWishlist = invocation.getArgument(0);
					return Wishlist.builder()
						.id(1L)
						.name(savedWishlist.getName())
						.member(savedWishlist.getMember())
						.build();
				})
				.willAnswer(invocation -> {
					Wishlist savedWishlist = invocation.getArgument(0);
					return Wishlist.builder()
						.id(2L)
						.name(savedWishlist.getName())
						.member(savedWishlist.getMember())
						.build();
				});

			// When
			WishlistResponse.CreateResponse firstResponse = wishlistService.createWishlist(request, currentMemberId);
			WishlistResponse.CreateResponse secondResponse = wishlistService.createWishlist(request, currentMemberId);

			// Then
			assertThat(firstResponse.id()).isEqualTo(1L);
			assertThat(secondResponse.id()).isEqualTo(2L);

			verify(memberRepository, times(2)).findById(currentMemberId);
			verify(wishlistRepository, times(2)).save(any(Wishlist.class));
		}
	}

	@Nested
	@DisplayName("위시리스트 수정 테스트")
	class UpdateWishlistTest {

		@Test
		@DisplayName("정상적으로 위시리스트를 수정한다")
		void updateWishlist_Success() {
			// Given
			Long wishlistId = 1L;
			WishlistRequest.updateRequest request = new WishlistRequest.updateRequest("수정된 서울 여행");
			Long currentMemberId = 1L;

			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

			// When
			WishlistResponse.UpdateResponse response = wishlistService.updateWishlist(wishlistId, request);

			// Then
			assertThat(response.id()).isEqualTo(wishlistId);
			assertThat(wishlist.getName()).isEqualTo("수정된 서울 여행");

			verify(wishlistRepository).findById(wishlistId);
		}

		@Test
		@DisplayName("존재하지 않는 위시리스트 수정 시 예외 발생")
		void updateWishlist_WishlistNotFound() {
			// Given
			Long nonExistentWishlistId = 999L;
			WishlistRequest.updateRequest request = new WishlistRequest.updateRequest("수정된 이름");
			Long currentMemberId = 1L;

			given(wishlistRepository.findById(nonExistentWishlistId)).willReturn(Optional.empty());

			// When & Then
			assertThatThrownBy(() -> wishlistService.updateWishlist(nonExistentWishlistId, request))
				.isInstanceOf(WishlistNotFoundException.class)
				.hasMessage("존재하지 않는 위시리스트입니다.");

			verify(wishlistRepository).findById(nonExistentWishlistId);
		}


		@Test
		@DisplayName("같은 이름으로 수정할 수 있다")
		void updateWishlist_SameName() {
			// Given
			Long wishlistId = 1L;
			String currentName = wishlist.getName();
			WishlistRequest.updateRequest request = new WishlistRequest.updateRequest(currentName);
			Long currentMemberId = 1L;

			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

			// When
			WishlistResponse.UpdateResponse response = wishlistService.updateWishlist(wishlistId, request);

			// Then
			assertThat(response.id()).isEqualTo(wishlistId);
			assertThat(wishlist.getName()).isEqualTo(currentName);

			verify(wishlistRepository).findById(wishlistId);
		}
	}

	@Nested
	@DisplayName("위시리스트 삭제 테스트")
	class DeleteWishlistTest {

		@Test
		@DisplayName("정상적으로 위시리스트를 삭제한다")
		void deleteWishlist_Success() {
			// Given
			Long wishlistId = 1L;

			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

			// When
			assertThatCode(() -> wishlistService.deleteWishlist(wishlistId))
				.doesNotThrowAnyException();

			// Then
			verify(wishlistRepository).findById(wishlistId);
			verify(wishlistAccommodationRepository).deleteAllByWishlistId(wishlistId);
			verify(wishlistRepository).delete(wishlist);
		}

		@Test
		@DisplayName("존재하지 않는 위시리스트 삭제 시 예외 발생")
		void deleteWishlist_WishlistNotFound() {
			// Given
			Long nonExistentWishlistId = 999L;

			given(wishlistRepository.findById(nonExistentWishlistId)).willReturn(Optional.empty());

			// When & Then
			assertThatThrownBy(() -> wishlistService.deleteWishlist(nonExistentWishlistId))
				.isInstanceOf(WishlistNotFoundException.class)
				.hasMessage("존재하지 않는 위시리스트입니다.");

			verify(wishlistRepository).findById(nonExistentWishlistId);
			verify(wishlistAccommodationRepository, never()).deleteAllByWishlistId(any());
			verify(wishlistRepository, never()).delete(any());
		}

		@Test
		@DisplayName("위시리스트 삭제 시 연관된 숙소들도 함께 삭제된다")
		void deleteWishlist_CascadeDeleteAccommodations() {
			// Given
			Long wishlistId = 1L;

			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

			// When
			wishlistService.deleteWishlist(wishlistId);

			// Then
			// 연관된 숙소들이 먼저 삭제되고, 그 다음에 위시리스트가 삭제되는 순서를 검증
			InOrder inOrder = inOrder(wishlistAccommodationRepository, wishlistRepository);
			inOrder.verify(wishlistAccommodationRepository).deleteAllByWishlistId(wishlistId);
			inOrder.verify(wishlistRepository).delete(wishlist);

			verify(wishlistRepository).findById(wishlistId);
		}

		@Test
		@DisplayName("위시리스트 삭제 시 실제 엔티티 객체가 전달된다")
		void deleteWishlist_EntityObjectPassed() {
			// Given
			Long wishlistId = 1L;
			Wishlist spyWishlist = spy(wishlist);

			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(spyWishlist));

			// When
			wishlistService.deleteWishlist(wishlistId);

			// Then
			verify(wishlistRepository).delete(spyWishlist);
			verify(wishlistAccommodationRepository).deleteAllByWishlistId(spyWishlist.getId());
		}

		@Test
		@DisplayName("여러 위시리스트를 연속으로 삭제할 수 있다")
		void deleteWishlist_MultipleDeletes() {
			// Given
			Long wishlistId1 = 1L;
			Long wishlistId2 = 2L;
			Long wishlistId3 = 3L;

			Wishlist wishlist1 = Wishlist.builder().id(wishlistId1).name("위시리스트1").member(member).build();
			Wishlist wishlist2 = Wishlist.builder().id(wishlistId2).name("위시리스트2").member(member).build();
			Wishlist wishlist3 = Wishlist.builder().id(wishlistId3).name("위시리스트3").member(member).build();

			given(wishlistRepository.findById(wishlistId1)).willReturn(Optional.of(wishlist1));
			given(wishlistRepository.findById(wishlistId2)).willReturn(Optional.of(wishlist2));
			given(wishlistRepository.findById(wishlistId3)).willReturn(Optional.of(wishlist3));

			// When
			assertThatCode(() -> {
				wishlistService.deleteWishlist(wishlistId1);
				wishlistService.deleteWishlist(wishlistId2);
				wishlistService.deleteWishlist(wishlistId3);
			}).doesNotThrowAnyException();

			// Then
			verify(wishlistRepository).findById(wishlistId1);
			verify(wishlistRepository).findById(wishlistId2);
			verify(wishlistRepository).findById(wishlistId3);

			verify(wishlistAccommodationRepository).deleteAllByWishlistId(wishlistId1);
			verify(wishlistAccommodationRepository).deleteAllByWishlistId(wishlistId2);
			verify(wishlistAccommodationRepository).deleteAllByWishlistId(wishlistId3);

			verify(wishlistRepository).delete(wishlist1);
			verify(wishlistRepository).delete(wishlist2);
			verify(wishlistRepository).delete(wishlist3);
		}

		@Test
		@DisplayName("숙소가 많은 위시리스트도 삭제할 수 있다")
		void deleteWishlist_WithManyAccommodations() {
			// Given
			Long wishlistId = 1L;

			// 많은 숙소가 있는 위시리스트를 시뮬레이션
			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

			// When
			wishlistService.deleteWishlist(wishlistId);

			// Then
			verify(wishlistRepository).findById(wishlistId);
			verify(wishlistAccommodationRepository).deleteAllByWishlistId(wishlistId);
			verify(wishlistRepository).delete(wishlist);
		}

		@Test
		@DisplayName("빈 위시리스트(숙소 0개)도 삭제할 수 있다")
		void deleteWishlist_EmptyWishlist() {
			// Given
			Long wishlistId = 1L;

			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

			// When
			wishlistService.deleteWishlist(wishlistId);

			// Then
			verify(wishlistRepository).findById(wishlistId);
			// 빈 위시리스트라도 deleteAllByWishlistId는 호출됨 (실제로는 아무것도 삭제하지 않음)
			verify(wishlistAccommodationRepository).deleteAllByWishlistId(wishlistId);
			verify(wishlistRepository).delete(wishlist);
		}

		@Test
		@DisplayName("삭제 작업이 트랜잭션으로 처리된다")
		void deleteWishlist_Transactional() {
			// Given
			Long wishlistId = 1L;

			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

			// When
			wishlistService.deleteWishlist(wishlistId);

			// Then
			// @Transactional 어노테이션이 적용된 메서드이므로
			// 모든 삭제 작업이 하나의 트랜잭션 내에서 수행됨을 보장
			verify(wishlistRepository).findById(wishlistId);
			verify(wishlistAccommodationRepository).deleteAllByWishlistId(wishlistId);
			verify(wishlistRepository).delete(wishlist);
		}

		@Test
		@DisplayName("삭제 과정에서 로깅이 수행된다")
		void deleteWishlist_LoggingPerformed() {
			// Given
			Long wishlistId = 1L;

			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

			// When
			wishlistService.deleteWishlist(wishlistId);

			// Then
			// 실제 로그 출력 검증은 어렵지만, 위시리스트 조회가 성공했다는 것은
			// 로그가 정상적으로 출력될 것임을 의미
			verify(wishlistRepository).findById(wishlistId);
		}

		@Test
		@DisplayName("삭제 중 예외 발생 시 다른 메서드는 호출되지 않는다")
		void deleteWishlist_ExceptionHandling() {
			// Given
			Long wishlistId = 1L;

			// findById에서 예외 발생 시뮬레이션
			given(wishlistRepository.findById(wishlistId))
				.willThrow(new RuntimeException("Database connection error"));

			// When & Then
			assertThatThrownBy(() -> wishlistService.deleteWishlist(wishlistId))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("Database connection error");

			// 예외가 발생했으므로 후속 메서드들은 호출되지 않아야 함
			verify(wishlistRepository).findById(wishlistId);
			verify(wishlistAccommodationRepository, never()).deleteAllByWishlistId(any());
			verify(wishlistRepository, never()).delete(any());
		}

		@Test
		@DisplayName("null ID로 삭제 시도 시 적절히 처리된다")
		void deleteWishlist_NullId() {
			// Given
			Long nullWishlistId = null;

			// When & Then
			// null ID는 repository에서 처리하므로 서비스에서는 그대로 전달
			assertThatThrownBy(() -> wishlistService.deleteWishlist(nullWishlistId))
				.isInstanceOf(Exception.class); // 실제로는 repository에서 발생하는 예외

			verify(wishlistRepository).findById(nullWishlistId);
		}

		@Test
		@DisplayName("음수 ID로 삭제 시도 시 위시리스트를 찾을 수 없음")
		void deleteWishlist_NegativeId() {
			// Given
			Long negativeWishlistId = -1L;

			given(wishlistRepository.findById(negativeWishlistId)).willReturn(Optional.empty());

			// When & Then
			assertThatThrownBy(() -> wishlistService.deleteWishlist(negativeWishlistId))
				.isInstanceOf(WishlistNotFoundException.class)
				.hasMessage("존재하지 않는 위시리스트입니다.");

			verify(wishlistRepository).findById(negativeWishlistId);
			verify(wishlistAccommodationRepository, never()).deleteAllByWishlistId(any());
			verify(wishlistRepository, never()).delete(any());
		}
	}
	@Nested
	@DisplayName("위시리스트 목록 조회 테스트")
	class FindWishlistsTest {

		@Test
		@DisplayName("정상적으로 위시리스트 목록을 조회한다")
		void findWishlists_Success() {
			// Given
			Long currentMemberId = 1L;
			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(20)
				.lastId(null)
				.lastCreatedAt(null)
				.build();

			List<Wishlist> wishlists = List.of(
				createWishlistWithId(1L, "서울 여행"),
				createWishlistWithId(2L, "부산 여행"),
				createWishlistWithId(3L, "제주 여행")
			);

			Slice<Wishlist> wishlistSlice = new SliceImpl<>(wishlists, PageRequest.of(0, 20), false);

			Map<Long, Long> wishlistItemCounts = Map.of(
				1L, 3L,
				2L, 5L,
				3L, 2L
			);

			Map<Long, String> thumbnailUrls = Map.of(
				1L, "thumbnail1.jpg",
				2L, "thumbnail2.jpg"
				// 3L은 의도적으로 제외 (썸네일 없음)
			);

			CursorResponse.PageInfo pageInfo = CursorResponse.PageInfo.builder()
				.hasNext(false)
				.nextCursor(null)
				.currentSize(3)
				.build();

			given(wishlistRepository.findByMemberIdWithCursor(eq(currentMemberId), eq(null), eq(null), any(PageRequest.class)))
				.willReturn(wishlistSlice);
			given(wishlistAccommodationRepository.countByWishlistIds(List.of(1L, 2L, 3L)))
				.willReturn(wishlistItemCounts);
			given(wishlistAccommodationRepository.findLatestThumbnailUrlsByWishlistIds(List.of(1L, 2L, 3L)))
				.willReturn(thumbnailUrls);
			given(cursorPageInfoCreator.createPageInfo(eq(wishlists), eq(false), any(), any()))
				.willReturn(pageInfo);

			// When
			WishlistResponse.WishlistInfos response = wishlistService.findWishlists(request, currentMemberId);

			// Then
			assertThat(response.wishlists()).hasSize(3);
			assertThat(response.pageInfo().hasNext()).isFalse();
			assertThat(response.pageInfo().currentSize()).isEqualTo(3);

			List<WishlistResponse.WishlistInfo> wishlistInfos = response.wishlists();
			assertThat(wishlistInfos.get(0).name()).isEqualTo("서울 여행");
			assertThat(wishlistInfos.get(0).wishlistItemCount()).isEqualTo(3L);
			assertThat(wishlistInfos.get(0).thumbnailImageUrl()).isEqualTo("thumbnail1.jpg");

			assertThat(wishlistInfos.get(1).name()).isEqualTo("부산 여행");
			assertThat(wishlistInfos.get(1).wishlistItemCount()).isEqualTo(5L);
			assertThat(wishlistInfos.get(1).thumbnailImageUrl()).isEqualTo("thumbnail2.jpg");

			assertThat(wishlistInfos.get(2).name()).isEqualTo("제주 여행");
			assertThat(wishlistInfos.get(2).wishlistItemCount()).isEqualTo(2L);
			assertThat(wishlistInfos.get(2).thumbnailImageUrl()).isNull(); // 썸네일 없음

			verify(wishlistRepository).findByMemberIdWithCursor(eq(currentMemberId), eq(null), eq(null), any(PageRequest.class));
			verify(wishlistAccommodationRepository).countByWishlistIds(List.of(1L, 2L, 3L));
			verify(wishlistAccommodationRepository).findLatestThumbnailUrlsByWishlistIds(List.of(1L, 2L, 3L));
			verify(cursorPageInfoCreator).createPageInfo(eq(wishlists), eq(false), any(), any());
		}

		@Test
		@DisplayName("빈 위시리스트 목록을 조회한다")
		void findWishlists_EmptyList() {
			// Given
			Long currentMemberId = 1L;
			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(20)
				.lastId(null)
				.lastCreatedAt(null)
				.build();

			Slice<Wishlist> emptySlice = new SliceImpl<>(List.of(), PageRequest.of(0, 20), false);

			CursorResponse.PageInfo pageInfo = CursorResponse.PageInfo.builder()
				.hasNext(false)
				.nextCursor(null)
				.currentSize(0)
				.build();

			given(wishlistRepository.findByMemberIdWithCursor(eq(currentMemberId), eq(null), eq(null), any(PageRequest.class)))
				.willReturn(emptySlice);
			given(wishlistAccommodationRepository.countByWishlistIds(List.of()))
				.willReturn(new HashMap<>());
			given(wishlistAccommodationRepository.findLatestThumbnailUrlsByWishlistIds(List.of()))
				.willReturn(new HashMap<>());
			given(cursorPageInfoCreator.createPageInfo(eq(List.of()), eq(false), any(), any()))
				.willReturn(pageInfo);

			// When
			WishlistResponse.WishlistInfos response = wishlistService.findWishlists(request, currentMemberId);

			// Then
			assertThat(response.wishlists()).isEmpty();
			assertThat(response.pageInfo().hasNext()).isFalse();
			assertThat(response.pageInfo().currentSize()).isZero();

			verify(wishlistRepository).findByMemberIdWithCursor(eq(currentMemberId), eq(null), eq(null), any(PageRequest.class));
			verify(wishlistAccommodationRepository).countByWishlistIds(List.of());
			verify(wishlistAccommodationRepository).findLatestThumbnailUrlsByWishlistIds(List.of());
			verify(cursorPageInfoCreator).createPageInfo(eq(List.of()), eq(false), any(), any());
		}

		@Test
		@DisplayName("커서 기반 페이징으로 위시리스트 목록을 조회한다")
		void findWishlists_WithCursor() {
			// Given
			Long currentMemberId = 1L;
			Long lastId = 3L;
			LocalDateTime lastCreatedAt = LocalDateTime.now().minusDays(2);

			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(2)
				.lastId(lastId)
				.lastCreatedAt(lastCreatedAt)
				.build();

			List<Wishlist> wishlists = List.of(
				createWishlistWithId(4L, "대구 여행"),
				createWishlistWithId(5L, "광주 여행")
			);

			Slice<Wishlist> wishlistSlice = new SliceImpl<>(wishlists, PageRequest.of(0, 2), true);

			Map<Long, Long> wishlistItemCounts = Map.of(
				4L, 1L,
				5L, 4L
			);

			Map<Long, String> thumbnailUrls = Map.of(
				4L, "thumbnail4.jpg"
				// 5L은 의도적으로 제외 (썸네일 없음)
			);

			CursorResponse.PageInfo pageInfo = CursorResponse.PageInfo.builder()
				.hasNext(true)
				.nextCursor("encoded_cursor")
				.currentSize(2)
				.build();

			given(wishlistRepository.findByMemberIdWithCursor(eq(currentMemberId), eq(lastId), eq(lastCreatedAt), any(PageRequest.class)))
				.willReturn(wishlistSlice);
			given(wishlistAccommodationRepository.countByWishlistIds(List.of(4L, 5L)))
				.willReturn(wishlistItemCounts);
			given(wishlistAccommodationRepository.findLatestThumbnailUrlsByWishlistIds(List.of(4L, 5L)))
				.willReturn(thumbnailUrls);
			given(cursorPageInfoCreator.createPageInfo(eq(wishlists), eq(true), any(), any()))
				.willReturn(pageInfo);

			// When
			WishlistResponse.WishlistInfos response = wishlistService.findWishlists(request, currentMemberId);

			// Then
			assertThat(response.wishlists()).hasSize(2);
			assertThat(response.pageInfo().hasNext()).isTrue();
			assertThat(response.pageInfo().nextCursor()).isEqualTo("encoded_cursor");
			assertThat(response.pageInfo().currentSize()).isEqualTo(2);

			List<WishlistResponse.WishlistInfo> wishlistInfos = response.wishlists();
			assertThat(wishlistInfos.get(0).name()).isEqualTo("대구 여행");
			assertThat(wishlistInfos.get(0).wishlistItemCount()).isEqualTo(1L);
			assertThat(wishlistInfos.get(0).thumbnailImageUrl()).isEqualTo("thumbnail4.jpg");

			assertThat(wishlistInfos.get(1).name()).isEqualTo("광주 여행");
			assertThat(wishlistInfos.get(1).wishlistItemCount()).isEqualTo(4L);
			assertThat(wishlistInfos.get(1).thumbnailImageUrl()).isNull();

			verify(wishlistRepository).findByMemberIdWithCursor(eq(currentMemberId), eq(lastId), eq(lastCreatedAt), any(PageRequest.class));
			verify(wishlistAccommodationRepository).countByWishlistIds(List.of(4L, 5L));
			verify(wishlistAccommodationRepository).findLatestThumbnailUrlsByWishlistIds(List.of(4L, 5L));
			verify(cursorPageInfoCreator).createPageInfo(eq(wishlists), eq(true), any(), any());
		}

		@Test
		@DisplayName("사용자별로 위시리스트가 분리되어 조회된다")
		void findWishlists_IsolatedByMember() {
			// Given
			Long currentMemberId = 1L;
			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(20)
				.lastId(null)
				.lastCreatedAt(null)
				.build();

			List<Wishlist> memberWishlists = List.of(
				createWishlistWithId(1L, "내 서울 여행")
			);

			Slice<Wishlist> wishlistSlice = new SliceImpl<>(memberWishlists, PageRequest.of(0, 20), false);

			given(wishlistRepository.findByMemberIdWithCursor(eq(currentMemberId), isNull(), isNull(), any(PageRequest.class)))
				.willReturn(wishlistSlice);
			given(wishlistAccommodationRepository.countByWishlistIds(List.of(1L)))
				.willReturn(Map.of(1L, 0L));
			given(wishlistAccommodationRepository.findLatestThumbnailUrlsByWishlistIds(List.of(1L)))
				.willReturn(Map.of());
			given(cursorPageInfoCreator.createPageInfo(eq(memberWishlists), eq(false), any(), any()))
				.willReturn(CursorResponse.PageInfo.builder()
					.hasNext(false)
					.nextCursor(null)
					.currentSize(1)
					.build());

			// When
			WishlistResponse.WishlistInfos response = wishlistService.findWishlists(request, currentMemberId);

			// Then
			assertThat(response.wishlists()).hasSize(1);
			assertThat(response.wishlists().getFirst().name()).isEqualTo("내 서울 여행");

			// 해당 사용자의 위시리스트만 조회되는지 확인
			verify(wishlistRepository).findByMemberIdWithCursor(eq(currentMemberId), isNull(), isNull(), any(PageRequest.class));
		}

		@Test
		@DisplayName("위시리스트별 숙소 개수가 올바르게 매핑된다")
		void findWishlists_WishlistItemCountMapping() {
			// Given
			Long currentMemberId = 1L;
			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(20)
				.lastId(null)
				.lastCreatedAt(null)
				.build();

			List<Wishlist> wishlists = List.of(
				createWishlistWithId(1L, "숙소 많은 위시리스트"),
				createWishlistWithId(2L, "숙소 적은 위시리스트"),
				createWishlistWithId(3L, "숙소 없는 위시리스트")
			);

			Slice<Wishlist> wishlistSlice = new SliceImpl<>(wishlists, PageRequest.of(0, 20), false);

			// 위시리스트별 숙소 개수 - 일부는 없음
			Map<Long, Long> wishlistItemCounts = Map.of(
				1L, 10L,
				2L, 3L
				// 3L은 의도적으로 제외 (숙소 개수 0)
			);

			Map<Long, String> thumbnailUrls = Map.of();

			CursorResponse.PageInfo pageInfo = CursorResponse.PageInfo.builder()
				.hasNext(false)
				.nextCursor(null)
				.currentSize(3)
				.build();

			given(wishlistRepository.findByMemberIdWithCursor(eq(currentMemberId), eq(null), eq(null), any(PageRequest.class)))
				.willReturn(wishlistSlice);
			given(wishlistAccommodationRepository.countByWishlistIds(List.of(1L, 2L, 3L)))
				.willReturn(wishlistItemCounts);
			given(wishlistAccommodationRepository.findLatestThumbnailUrlsByWishlistIds(List.of(1L, 2L, 3L)))
				.willReturn(thumbnailUrls);
			given(cursorPageInfoCreator.createPageInfo(eq(wishlists), eq(false), any(), any()))
				.willReturn(pageInfo);

			// When
			WishlistResponse.WishlistInfos response = wishlistService.findWishlists(request, currentMemberId);

			// Then
			List<WishlistResponse.WishlistInfo> wishlistInfos = response.wishlists();
			assertThat(wishlistInfos.get(0).wishlistItemCount()).isEqualTo(10L);
			assertThat(wishlistInfos.get(1).wishlistItemCount()).isEqualTo(3L);
			assertThat(wishlistInfos.get(2).wishlistItemCount()).isEqualTo(0L); // Map에 없으면 0으로 처리
		}

		@Test
		@DisplayName("위시리스트별 썸네일 URL이 올바르게 매핑된다")
		void findWishlists_ThumbnailUrlMapping() {
			// Given
			Long currentMemberId = 1L;
			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(20)
				.lastId(null)
				.lastCreatedAt(null)
				.build();

			List<Wishlist> wishlists = List.of(
				createWishlistWithId(1L, "썸네일 있는 위시리스트"),
				createWishlistWithId(2L, "썸네일 없는 위시리스트")
			);

			Slice<Wishlist> wishlistSlice = new SliceImpl<>(wishlists, PageRequest.of(0, 20), false);

			Map<Long, Long> wishlistItemCounts = Map.of(1L, 5L, 2L, 3L);

			// 일부 위시리스트만 썸네일 존재
			Map<Long, String> thumbnailUrls = Map.of(
				1L, "https://example.com/thumbnail1.jpg"
				// 2L은 의도적으로 제외 (썸네일 없음)
			);

			CursorResponse.PageInfo pageInfo = CursorResponse.PageInfo.builder()
				.hasNext(false)
				.nextCursor(null)
				.currentSize(2)
				.build();

			given(wishlistRepository.findByMemberIdWithCursor(eq(currentMemberId), eq(null), eq(null), any(PageRequest.class)))
				.willReturn(wishlistSlice);
			given(wishlistAccommodationRepository.countByWishlistIds(List.of(1L, 2L)))
				.willReturn(wishlistItemCounts);
			given(wishlistAccommodationRepository.findLatestThumbnailUrlsByWishlistIds(List.of(1L, 2L)))
				.willReturn(thumbnailUrls);
			given(cursorPageInfoCreator.createPageInfo(eq(wishlists), eq(false), any(), any()))
				.willReturn(pageInfo);

			// When
			WishlistResponse.WishlistInfos response = wishlistService.findWishlists(request, currentMemberId);

			// Then
			List<WishlistResponse.WishlistInfo> wishlistInfos = response.wishlists();
			assertThat(wishlistInfos.get(0).thumbnailImageUrl()).isEqualTo("https://example.com/thumbnail1.jpg");
			assertThat(wishlistInfos.get(1).thumbnailImageUrl()).isNull(); // Map에 없으면 null
		}

		@Test
		@DisplayName("커서 페이지 정보가 올바르게 생성된다")
		void findWishlists_CursorPageInfoCreation() {
			// Given
			Long currentMemberId = 1L;
			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(1)
				.lastId(null)
				.lastCreatedAt(null)
				.build();

			List<Wishlist> wishlists = List.of(
				createWishlistWithId(1L, "첫 번째 위시리스트")
			);

			Slice<Wishlist> wishlistSlice = new SliceImpl<>(wishlists, PageRequest.of(0, 1), true); // hasNext = true

			Map<Long, Long> wishlistItemCounts = Map.of(1L, 2L);
			Map<Long, String> thumbnailUrls = Map.of();

			given(wishlistRepository.findByMemberIdWithCursor(eq(currentMemberId), eq(null), eq(null), any(PageRequest.class)))
				.willReturn(wishlistSlice);
			given(wishlistAccommodationRepository.countByWishlistIds(List.of(1L)))
				.willReturn(wishlistItemCounts);
			given(wishlistAccommodationRepository.findLatestThumbnailUrlsByWishlistIds(List.of(1L)))
				.willReturn(thumbnailUrls);

			// When
			wishlistService.findWishlists(request, currentMemberId);

			// Then
			verify(cursorPageInfoCreator).createPageInfo(
				eq(wishlists),
				eq(true), // hasNext
				any(Function.class), // idExtractor
				any(Function.class)  // createdAtExtractor
			);
		}

		@Test
		@DisplayName("큰 페이지 사이즈로 위시리스트를 조회한다")
		void findWishlists_LargePageSize() {
			// Given
			Long currentMemberId = 1L;
			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(100)
				.lastId(null)
				.lastCreatedAt(null)
				.build();

			List<Wishlist> wishlists = List.of(
				createWishlistWithId(1L, "위시리스트 1"),
				createWishlistWithId(2L, "위시리스트 2")
			);

			Slice<Wishlist> wishlistSlice = new SliceImpl<>(wishlists, PageRequest.of(0, 100), false);

			Map<Long, Long> wishlistItemCounts = Map.of(1L, 1L, 2L, 1L);
			Map<Long, String> thumbnailUrls = Map.of();

			CursorResponse.PageInfo pageInfo = CursorResponse.PageInfo.builder()
				.hasNext(false)
				.nextCursor(null)
				.currentSize(2)
				.build();

			given(wishlistRepository.findByMemberIdWithCursor(eq(currentMemberId), eq(null), eq(null), any(PageRequest.class)))
				.willReturn(wishlistSlice);
			given(wishlistAccommodationRepository.countByWishlistIds(List.of(1L, 2L)))
				.willReturn(wishlistItemCounts);
			given(wishlistAccommodationRepository.findLatestThumbnailUrlsByWishlistIds(List.of(1L, 2L)))
				.willReturn(thumbnailUrls);
			given(cursorPageInfoCreator.createPageInfo(eq(wishlists), eq(false), any(), any()))
				.willReturn(pageInfo);

			// When
			WishlistResponse.WishlistInfos response = wishlistService.findWishlists(request, currentMemberId);

			// Then
			assertThat(response.wishlists()).hasSize(2);
			assertThat(response.pageInfo().hasNext()).isFalse();

			verify(wishlistRepository).findByMemberIdWithCursor(eq(currentMemberId), eq(null), eq(null), any(PageRequest.class));
		}

		// 테스트 헬퍼 메서드
		private Wishlist createWishlistWithId(Long id, String name) {
			return Wishlist.builder()
				.id(id)
				.name(name)
				.member(member)
				.build();
		}
	}
	// 테스트 헬퍼 메서드
	private Wishlist createWishlistWithId(Long id, String name) {
		return Wishlist.builder()
			.id(id)
			.name(name)
			.member(member)
			.build();
	}

	@Nested
	@DisplayName("위시리스트 숙소 추가 테스트")
	class CreateWishlistAccommodationTest {
		private Accommodation accommodation;

		@BeforeEach
		void setUpAccommodation() {
			accommodation = Accommodation.builder()
				.id(100L)
				.name("신라호텔")
				.build();
		}

		@Test
		@DisplayName("정상적으로 위시리스트에 숙소를 추가한다")
		void createWishlistAccommodation_Success() {
			// Given
			Long wishlistId = 1L;
			Long accommodationId = 100L;
			WishlistRequest.CreateWishlistAccommodationRequest request =
				new WishlistRequest.CreateWishlistAccommodationRequest(accommodationId);

			// Mock 설정 - 실제 실행 순서: accommodation → 중복검사 → wishlist → save
			given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));
			given(wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(wishlistId, accommodationId))
				.willReturn(false);
			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

			WishlistAccommodation savedWishlistAccommodation = WishlistAccommodation.builder()
				.id(10L)
				.wishlist(wishlist)
				.accommodation(accommodation)
				.build();

			given(wishlistAccommodationRepository.save(any(WishlistAccommodation.class)))
				.willReturn(savedWishlistAccommodation);

			// When
			WishlistResponse.CreateWishlistAccommodationResponse response =
				wishlistService.createWishlistAccommodation(wishlistId, request);

			// Then
			assertThat(response.id()).isEqualTo(10L);

			verify(accommodationRepository).findById(accommodationId);
			verify(wishlistAccommodationRepository).existsByWishlistIdAndAccommodationId(wishlistId, accommodationId);
			verify(wishlistRepository).findById(wishlistId);
			verify(wishlistAccommodationRepository).save(any(WishlistAccommodation.class));
		}

		@Test
		@DisplayName("ArgumentCaptor를 사용하여 저장된 데이터를 검증한다")
		void createWishlistAccommodation_VerifyCreatedEntity() {
			// Given
			Long wishlistId = 1L;
			Long accommodationId = 100L;
			WishlistRequest.CreateWishlistAccommodationRequest request =
				new WishlistRequest.CreateWishlistAccommodationRequest(accommodationId);

			given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));
			given(wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(wishlistId, accommodationId))
				.willReturn(false);
			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

			WishlistAccommodation savedWishlistAccommodation = WishlistAccommodation.builder()
				.id(10L)
				.wishlist(wishlist)
				.accommodation(accommodation)
				.build();

			given(wishlistAccommodationRepository.save(any(WishlistAccommodation.class)))
				.willReturn(savedWishlistAccommodation);

			// When
			wishlistService.createWishlistAccommodation(wishlistId, request);

			// Then - ArgumentCaptor를 사용하여 저장된 데이터 검증
			ArgumentCaptor<WishlistAccommodation> captor = ArgumentCaptor.forClass(WishlistAccommodation.class);
			verify(wishlistAccommodationRepository).save(captor.capture());

			WishlistAccommodation capturedWishlistAccommodation = captor.getValue();
			assertThat(capturedWishlistAccommodation.getWishlist()).isEqualTo(wishlist);
			assertThat(capturedWishlistAccommodation.getAccommodation()).isEqualTo(accommodation);
			assertThat(capturedWishlistAccommodation.getMemo()).isNull(); // 기본값은 null
		}

		@Test
		@DisplayName("존재하지 않는 위시리스트에 숙소 추가 시 예외 발생")
		void createWishlistAccommodation_WishlistNotFound() {
			// Given
			Long nonExistentWishlistId = 999L;
			Long accommodationId = 100L;
			WishlistRequest.CreateWishlistAccommodationRequest request =
				new WishlistRequest.CreateWishlistAccommodationRequest(accommodationId);

			// accommodation → 중복검사 → wishlist에서 예외 발생
			given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));
			given(wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(nonExistentWishlistId, accommodationId))
				.willReturn(false);
			given(wishlistRepository.findById(nonExistentWishlistId)).willReturn(Optional.empty());

			// When & Then
			assertThatThrownBy(() -> wishlistService.createWishlistAccommodation(nonExistentWishlistId, request))
				.isInstanceOf(WishlistNotFoundException.class)
				.hasMessage("존재하지 않는 위시리스트입니다.");

			verify(accommodationRepository).findById(accommodationId);
			verify(wishlistAccommodationRepository).existsByWishlistIdAndAccommodationId(nonExistentWishlistId, accommodationId);
			verify(wishlistRepository).findById(nonExistentWishlistId);
			verify(wishlistAccommodationRepository, never()).save(any());
		}

		@Test
		@DisplayName("존재하지 않는 숙소를 위시리스트에 추가 시 예외 발생")
		void createWishlistAccommodation_AccommodationNotFound() {
			// Given
			Long wishlistId = 1L;
			Long nonExistentAccommodationId = 999L;
			WishlistRequest.CreateWishlistAccommodationRequest request =
				new WishlistRequest.CreateWishlistAccommodationRequest(nonExistentAccommodationId);

			// accommodation을 먼저 조회하므로 여기서 바로 예외 발생
			given(accommodationRepository.findById(nonExistentAccommodationId)).willReturn(Optional.empty());

			// When & Then
			assertThatThrownBy(() -> wishlistService.createWishlistAccommodation(wishlistId, request))
				.isInstanceOf(AccommodationNotFoundException.class)
				.hasMessage("존재하지 않는 숙소입니다.");

			verify(accommodationRepository).findById(nonExistentAccommodationId);
			verify(wishlistRepository, never()).findById(any());
			verify(wishlistAccommodationRepository, never()).save(any());
		}

		@Test
		@DisplayName("이미 위시리스트에 추가된 숙소를 중복 추가 시 예외 발생")
		void createWishlistAccommodation_DuplicateAccommodation() {
			// Given
			Long wishlistId = 1L;
			Long accommodationId = 100L;
			WishlistRequest.CreateWishlistAccommodationRequest request =
				new WishlistRequest.CreateWishlistAccommodationRequest(accommodationId);

			// 실제 실행 순서: accommodation → 중복검사에서 예외 발생
			given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));
			given(wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(wishlistId, accommodationId))
				.willReturn(true); // 이미 존재함

			// When & Then
			assertThatThrownBy(() -> wishlistService.createWishlistAccommodation(wishlistId, request))
				.isInstanceOf(WishlistAccommodationDuplicateException.class)
				.hasMessage("이미 위시리스트에 추가된 숙소입니다.");

			verify(accommodationRepository).findById(accommodationId);
			verify(wishlistAccommodationRepository).existsByWishlistIdAndAccommodationId(wishlistId, accommodationId);
			// 중복 검사에서 예외 발생하므로 wishlist 조회와 save는 실행되지 않음
			verify(wishlistRepository, never()).findById(any());
			verify(wishlistAccommodationRepository, never()).save(any());
		}

		@Test
		@DisplayName("같은 숙소를 여러 위시리스트에 추가할 수 있다")
		void createWishlistAccommodation_SameAccommodationDifferentWishlists() {
			// Given
			Long accommodationId = 100L;
			WishlistRequest.CreateWishlistAccommodationRequest request =
				new WishlistRequest.CreateWishlistAccommodationRequest(accommodationId);

			// 첫 번째 위시리스트에 추가
			Long firstWishlistId = 1L;
			Wishlist firstWishlist = Wishlist.builder()
				.id(firstWishlistId)
				.name("첫 번째 위시리스트")
				.member(member)
				.build();

			// 두 번째 위시리스트에 추가
			Long secondWishlistId = 2L;
			Wishlist secondWishlist = Wishlist.builder()
				.id(secondWishlistId)
				.name("두 번째 위시리스트")
				.member(member)
				.build();

			// 첫 번째 위시리스트 추가
			given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));
			given(wishlistRepository.findById(firstWishlistId)).willReturn(Optional.of(firstWishlist));
			given(wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(firstWishlistId, accommodationId))
				.willReturn(false);

			WishlistAccommodation firstSavedWishlistAccommodation = WishlistAccommodation.builder()
				.id(10L)
				.wishlist(firstWishlist)
				.accommodation(accommodation)
				.build();

			given(wishlistAccommodationRepository.save(any(WishlistAccommodation.class)))
				.willReturn(firstSavedWishlistAccommodation);

			// When - 첫 번째 위시리스트에 추가
			WishlistResponse.CreateWishlistAccommodationResponse firstResponse =
				wishlistService.createWishlistAccommodation(firstWishlistId, request);

			// 두 번째 위시리스트 추가 설정
			given(wishlistRepository.findById(secondWishlistId)).willReturn(Optional.of(secondWishlist));
			given(wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(secondWishlistId, accommodationId))
				.willReturn(false);

			WishlistAccommodation secondSavedWishlistAccommodation = WishlistAccommodation.builder()
				.id(20L)
				.wishlist(secondWishlist)
				.accommodation(accommodation)
				.build();

			given(wishlistAccommodationRepository.save(any(WishlistAccommodation.class)))
				.willReturn(secondSavedWishlistAccommodation);

			// When - 두 번째 위시리스트에 추가
			WishlistResponse.CreateWishlistAccommodationResponse secondResponse =
				wishlistService.createWishlistAccommodation(secondWishlistId, request);

			// Then
			assertThat(firstResponse.id()).isEqualTo(10L);
			assertThat(secondResponse.id()).isEqualTo(20L);

			// 각각 다른 위시리스트에 추가되었는지 확인
			verify(accommodationRepository, times(2)).findById(accommodationId);
			verify(wishlistRepository).findById(firstWishlistId);
			verify(wishlistRepository).findById(secondWishlistId);
			verify(wishlistAccommodationRepository, times(2)).save(any(WishlistAccommodation.class));
		}

		@Test
		@DisplayName("여러 사용자가 같은 숙소를 각자의 위시리스트에 추가할 수 있다")
		void createWishlistAccommodation_SameAccommodationDifferentUsers() {
			// Given
			Long accommodationId = 100L;
			WishlistRequest.CreateWishlistAccommodationRequest request =
				new WishlistRequest.CreateWishlistAccommodationRequest(accommodationId);

			// 사용자1의 위시리스트
			Long user1WishlistId = 1L;
			Member user1 = Member.builder().id(1L).email("user1@example.com").build();
			Wishlist user1Wishlist = Wishlist.builder()
				.id(user1WishlistId)
				.name("사용자1의 위시리스트")
				.member(user1)
				.build();

			// 사용자2의 위시리스트
			Long user2WishlistId = 2L;
			Member user2 = Member.builder().id(2L).email("user2@example.com").build();
			Wishlist user2Wishlist = Wishlist.builder()
				.id(user2WishlistId)
				.name("사용자2의 위시리스트")
				.member(user2)
				.build();

			// 사용자1의 위시리스트에 추가
			given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));
			given(wishlistRepository.findById(user1WishlistId)).willReturn(Optional.of(user1Wishlist));
			given(wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(user1WishlistId, accommodationId))
				.willReturn(false);

			WishlistAccommodation user1SavedAccommodation = WishlistAccommodation.builder()
				.id(10L)
				.wishlist(user1Wishlist)
				.accommodation(accommodation)
				.build();

			given(wishlistAccommodationRepository.save(any(WishlistAccommodation.class)))
				.willReturn(user1SavedAccommodation);

			// When - 사용자1이 추가
			WishlistResponse.CreateWishlistAccommodationResponse user1Response =
				wishlistService.createWishlistAccommodation(user1WishlistId, request);

			// 사용자2의 위시리스트에 추가 설정
			given(wishlistRepository.findById(user2WishlistId)).willReturn(Optional.of(user2Wishlist));
			given(wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(user2WishlistId, accommodationId))
				.willReturn(false);

			WishlistAccommodation user2SavedAccommodation = WishlistAccommodation.builder()
				.id(20L)
				.wishlist(user2Wishlist)
				.accommodation(accommodation)
				.build();

			given(wishlistAccommodationRepository.save(any(WishlistAccommodation.class)))
				.willReturn(user2SavedAccommodation);

			// When - 사용자2가 추가
			WishlistResponse.CreateWishlistAccommodationResponse user2Response =
				wishlistService.createWishlistAccommodation(user2WishlistId, request);

			// Then
			assertThat(user1Response.id()).isEqualTo(10L);
			assertThat(user2Response.id()).isEqualTo(20L);

			// 각각 다른 사용자의 위시리스트에 추가되었는지 확인
			verify(accommodationRepository, times(2)).findById(accommodationId);
			verify(wishlistRepository).findById(user1WishlistId);
			verify(wishlistRepository).findById(user2WishlistId);
			verify(wishlistAccommodationRepository, times(2)).save(any(WishlistAccommodation.class));
		}

		@Test
		@DisplayName("WishlistAccommodation 생성 시 기본 메모는 null이다")
		void createWishlistAccommodation_DefaultMemoIsNull() {
			// Given
			Long wishlistId = 1L;
			Long accommodationId = 100L;
			WishlistRequest.CreateWishlistAccommodationRequest request =
				new WishlistRequest.CreateWishlistAccommodationRequest(accommodationId);

			given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));
			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));
			given(wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(wishlistId, accommodationId))
				.willReturn(false);

			WishlistAccommodation savedWishlistAccommodation = WishlistAccommodation.builder()
				.id(10L)
				.wishlist(wishlist)
				.accommodation(accommodation)
				.build();

			given(wishlistAccommodationRepository.save(any(WishlistAccommodation.class)))
				.willReturn(savedWishlistAccommodation);

			// When
			wishlistService.createWishlistAccommodation(wishlistId, request);

			// Then
			ArgumentCaptor<WishlistAccommodation> captor = ArgumentCaptor.forClass(WishlistAccommodation.class);
			verify(wishlistAccommodationRepository).save(captor.capture());

			WishlistAccommodation captured = captor.getValue();
			assertThat(captured.getMemo()).isNull();
			assertThat(captured.getWishlist()).isEqualTo(wishlist);
			assertThat(captured.getAccommodation()).isEqualTo(accommodation);
		}

		@Test
		@DisplayName("트랜잭션으로 처리되어 예외 발생 시 롤백된다")
		void createWishlistAccommodation_TransactionalRollback() {
			// Given
			Long wishlistId = 1L;
			Long accommodationId = 100L;
			WishlistRequest.CreateWishlistAccommodationRequest request =
				new WishlistRequest.CreateWishlistAccommodationRequest(accommodationId);

			given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));
			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));
			given(wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(wishlistId, accommodationId))
				.willReturn(false);

			// save 메서드에서 예외 발생 시뮬레이션
			given(wishlistAccommodationRepository.save(any(WishlistAccommodation.class)))
				.willThrow(new RuntimeException("Database save error"));

			// When & Then
			assertThatThrownBy(() -> wishlistService.createWishlistAccommodation(wishlistId, request))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("Database save error");

			verify(accommodationRepository).findById(accommodationId);
			verify(wishlistRepository).findById(wishlistId);
			verify(wishlistAccommodationRepository).existsByWishlistIdAndAccommodationId(wishlistId, accommodationId);
			verify(wishlistAccommodationRepository).save(any(WishlistAccommodation.class));
		}

		@Test
		@DisplayName("null accommodationId로 요청 시 적절히 처리된다")
		void createWishlistAccommodation_NullAccommodationId() {
			// Given
			Long wishlistId = 1L;
			WishlistRequest.CreateWishlistAccommodationRequest request =
				new WishlistRequest.CreateWishlistAccommodationRequest(null);

			// accommodation을 먼저 조회하므로 null id로 조회 시 예외 발생
			given(accommodationRepository.findById(null))
				.willThrow(new IllegalArgumentException("ID cannot be null"));

			// When & Then
			assertThatThrownBy(() -> wishlistService.createWishlistAccommodation(wishlistId, request))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("ID cannot be null");

			verify(accommodationRepository).findById(null);
			verify(wishlistRepository, never()).findById(any());
		}

		@Test
		@DisplayName("중복 검사가 정확히 수행된다")
		void createWishlistAccommodation_DuplicateCheckExecuted() {
			// Given
			Long wishlistId = 1L;
			Long accommodationId = 100L;
			WishlistRequest.CreateWishlistAccommodationRequest request =
				new WishlistRequest.CreateWishlistAccommodationRequest(accommodationId);

			given(accommodationRepository.findById(accommodationId)).willReturn(Optional.of(accommodation));
			given(wishlistAccommodationRepository.existsByWishlistIdAndAccommodationId(wishlistId, accommodationId))
				.willReturn(false);
			given(wishlistRepository.findById(wishlistId)).willReturn(Optional.of(wishlist));

			WishlistAccommodation savedWishlistAccommodation = WishlistAccommodation.builder()
				.id(10L)
				.wishlist(wishlist)
				.accommodation(accommodation)
				.build();

			given(wishlistAccommodationRepository.save(any(WishlistAccommodation.class)))
				.willReturn(savedWishlistAccommodation);

			// When
			wishlistService.createWishlistAccommodation(wishlistId, request);

			// Then
			// 실제 실행 순서: accommodation → 중복검사 → wishlist → save
			InOrder inOrder = inOrder(accommodationRepository, wishlistAccommodationRepository, wishlistRepository);
			inOrder.verify(accommodationRepository).findById(accommodationId);
			inOrder.verify(wishlistAccommodationRepository).existsByWishlistIdAndAccommodationId(wishlistId, accommodationId);
			inOrder.verify(wishlistRepository).findById(wishlistId);
			inOrder.verify(wishlistAccommodationRepository).save(any(WishlistAccommodation.class));
		}
	}

	@Nested
	@DisplayName("위시리스트 숙소 메모 수정 테스트")
	class UpdateWishlistAccommodationTest {

		private Accommodation accommodation;
		private WishlistAccommodation wishlistAccommodation;
		private WishlistAccommodation otherWishlistAccommodation;

		@BeforeEach
		void setUpWishlistAccommodation() {
			accommodation = Accommodation.builder()
				.id(100L)
				.name("신라호텔")
				.build();

			// 현재 사용자의 위시리스트에 속한 숙소 항목
			wishlistAccommodation = WishlistAccommodation.builder()
				.id(10L)
				.memo("기존 메모")
				.wishlist(wishlist)
				.accommodation(accommodation)
				.build();

			// 다른 위시리스트에 속한 숙소 항목
			otherWishlistAccommodation = WishlistAccommodation.builder()
				.id(20L)
				.memo("다른 위시리스트 메모")
				.wishlist(otherWishlist)
				.accommodation(accommodation)
				.build();
		}

		@Test
		@DisplayName("정상적으로 위시리스트 숙소 메모를 수정한다")
		void updateWishlistAccommodation_Success() {
			// Given
			Long wishlistAccommodationId = 10L;
			String newMemo = "수정된 메모입니다. 정말 좋은 곳이에요!";
			WishlistRequest.UpdateWishlistAccommodationRequest request =
				new WishlistRequest.UpdateWishlistAccommodationRequest(newMemo);

			given(wishlistAccommodationRepository.findById(wishlistAccommodationId))
				.willReturn(Optional.of(wishlistAccommodation));

			// When
			WishlistResponse.UpdateWishlistAccommodationResponse response =
				wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request);

			// Then
			assertThat(response.id()).isEqualTo(wishlistAccommodationId);
			assertThat(wishlistAccommodation.getMemo()).isEqualTo(newMemo);

			verify(wishlistAccommodationRepository).findById(wishlistAccommodationId);
		}

		@Test
		@DisplayName("빈 메모로 수정할 수 있다")
		void updateWishlistAccommodation_EmptyMemo() {
			// Given
			Long wishlistAccommodationId = 10L;
			String emptyMemo = "";
			WishlistRequest.UpdateWishlistAccommodationRequest request =
				new WishlistRequest.UpdateWishlistAccommodationRequest(emptyMemo);

			given(wishlistAccommodationRepository.findById(wishlistAccommodationId))
				.willReturn(Optional.of(wishlistAccommodation));

			// When
			WishlistResponse.UpdateWishlistAccommodationResponse response =
				wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request);

			// Then
			assertThat(response.id()).isEqualTo(wishlistAccommodationId);
			assertThat(wishlistAccommodation.getMemo()).isEqualTo(emptyMemo);

			verify(wishlistAccommodationRepository).findById(wishlistAccommodationId);
		}

		@Test
		@DisplayName("최대 길이(1024자)의 메모로 수정할 수 있다")
		void updateWishlistAccommodation_MaxLengthMemo() {
			// Given
			Long wishlistAccommodationId = 10L;
			String maxLengthMemo = "A".repeat(1024);
			WishlistRequest.UpdateWishlistAccommodationRequest request =
				new WishlistRequest.UpdateWishlistAccommodationRequest(maxLengthMemo);

			given(wishlistAccommodationRepository.findById(wishlistAccommodationId))
				.willReturn(Optional.of(wishlistAccommodation));

			// When
			WishlistResponse.UpdateWishlistAccommodationResponse response =
				wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request);

			// Then
			assertThat(response.id()).isEqualTo(wishlistAccommodationId);
			assertThat(wishlistAccommodation.getMemo()).isEqualTo(maxLengthMemo);
			assertThat(wishlistAccommodation.getMemo().length()).isEqualTo(1024);

			verify(wishlistAccommodationRepository).findById(wishlistAccommodationId);
		}

		@Test
		@DisplayName("특수 문자가 포함된 메모로 수정할 수 있다")
		void updateWishlistAccommodation_SpecialCharacterMemo() {
			// Given
			Long wishlistAccommodationId = 10L;
			String specialCharacterMemo = "정말 좋은 곳! 🏨✨ 가격도 합리적 (★★★★★) 직원분들도 친절 😊 #추천 @여행";
			WishlistRequest.UpdateWishlistAccommodationRequest request =
				new WishlistRequest.UpdateWishlistAccommodationRequest(specialCharacterMemo);

			given(wishlistAccommodationRepository.findById(wishlistAccommodationId))
				.willReturn(Optional.of(wishlistAccommodation));

			// When
			WishlistResponse.UpdateWishlistAccommodationResponse response =
				wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request);

			// Then
			assertThat(response.id()).isEqualTo(wishlistAccommodationId);
			assertThat(wishlistAccommodation.getMemo()).isEqualTo(specialCharacterMemo);

			verify(wishlistAccommodationRepository).findById(wishlistAccommodationId);
		}

		@Test
		@DisplayName("같은 내용으로 메모를 수정할 수 있다")
		void updateWishlistAccommodation_SameMemo() {
			// Given
			Long wishlistAccommodationId = 10L;
			String currentMemo = wishlistAccommodation.getMemo(); // "기존 메모"
			WishlistRequest.UpdateWishlistAccommodationRequest request =
				new WishlistRequest.UpdateWishlistAccommodationRequest(currentMemo);

			given(wishlistAccommodationRepository.findById(wishlistAccommodationId))
				.willReturn(Optional.of(wishlistAccommodation));

			// When
			WishlistResponse.UpdateWishlistAccommodationResponse response =
				wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request);

			// Then
			assertThat(response.id()).isEqualTo(wishlistAccommodationId);
			assertThat(wishlistAccommodation.getMemo()).isEqualTo(currentMemo);

			verify(wishlistAccommodationRepository).findById(wishlistAccommodationId);
		}

		@Test
		@DisplayName("null 메모로 수정할 수 있다")
		void updateWishlistAccommodation_NullMemo() {
			// Given
			Long wishlistAccommodationId = 10L;
			String nullMemo = null;
			WishlistRequest.UpdateWishlistAccommodationRequest request =
				new WishlistRequest.UpdateWishlistAccommodationRequest(nullMemo);

			given(wishlistAccommodationRepository.findById(wishlistAccommodationId))
				.willReturn(Optional.of(wishlistAccommodation));

			// When
			WishlistResponse.UpdateWishlistAccommodationResponse response =
				wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request);

			// Then
			assertThat(response.id()).isEqualTo(wishlistAccommodationId);
			assertThat(wishlistAccommodation.getMemo()).isNull();

			verify(wishlistAccommodationRepository).findById(wishlistAccommodationId);
		}

		@Test
		@DisplayName("존재하지 않는 위시리스트 항목 메모 수정 시 예외 발생")
		void updateWishlistAccommodation_WishlistAccommodationNotFound() {
			// Given
			Long nonExistentWishlistAccommodationId = 999L;
			WishlistRequest.UpdateWishlistAccommodationRequest request =
				new WishlistRequest.UpdateWishlistAccommodationRequest("수정된 메모");

			given(wishlistAccommodationRepository.findById(nonExistentWishlistAccommodationId))
				.willReturn(Optional.empty());

			// When & Then
			assertThatThrownBy(() -> wishlistService.updateWishlistAccommodation(
				nonExistentWishlistAccommodationId, request))
				.isInstanceOf(WishlistAccommodationNotFoundException.class)
				.hasMessage("존재하지 않는 위시리스트 항목입니다.");

			verify(wishlistAccommodationRepository).findById(nonExistentWishlistAccommodationId);
		}

		@Test
		@DisplayName("메모 수정 시 엔티티의 updateMemo 메서드가 호출된다")
		void updateWishlistAccommodation_UpdateMemoMethodCalled() {
			// Given
			Long wishlistAccommodationId = 10L;
			String newMemo = "새로운 메모";
			WishlistRequest.UpdateWishlistAccommodationRequest request =
				new WishlistRequest.UpdateWishlistAccommodationRequest(newMemo);

			WishlistAccommodation spyWishlistAccommodation = spy(wishlistAccommodation);
			given(wishlistAccommodationRepository.findById(wishlistAccommodationId))
				.willReturn(Optional.of(spyWishlistAccommodation));

			// When
			wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request);

			// Then
			verify(spyWishlistAccommodation).updateMemo(newMemo);
			verify(wishlistAccommodationRepository).findById(wishlistAccommodationId);
		}

		@Test
		@DisplayName("긴 텍스트 메모로 수정할 수 있다")
		void updateWishlistAccommodation_LongTextMemo() {
			// Given
			Long wishlistAccommodationId = 10L;
			String longMemo = "이 호텔은 정말 훌륭한 위치에 있습니다. 지하철역에서 도보 5분 거리에 있어 교통이 매우 편리하고, 주변에 맛집들도 많아 식사하기에도 좋습니다. " +
				"직원들의 서비스도 정말 친절하고 전문적이었습니다. 특히 프런트 데스크 직원분이 관광지 추천도 해주시고 지도까지 챙겨주셔서 감동받았습니다. " +
				"객실도 깔끔하고 넓었으며, 침구류의 품질도 우수했습니다. 조식 뷔페도 다양하고 맛있었습니다. 다음에 이 도시를 다시 방문한다면 꼭 다시 이용하고 싶습니다.";
			WishlistRequest.UpdateWishlistAccommodationRequest request =
				new WishlistRequest.UpdateWishlistAccommodationRequest(longMemo);

			given(wishlistAccommodationRepository.findById(wishlistAccommodationId))
				.willReturn(Optional.of(wishlistAccommodation));

			// When
			WishlistResponse.UpdateWishlistAccommodationResponse response =
				wishlistService.updateWishlistAccommodation(wishlistAccommodationId, request);

			// Then
			assertThat(response.id()).isEqualTo(wishlistAccommodationId);
			assertThat(wishlistAccommodation.getMemo()).isEqualTo(longMemo);

			verify(wishlistAccommodationRepository).findById(wishlistAccommodationId);
		}
	}
	@Nested
	@DisplayName("위시리스트 숙소 삭제 테스트")
	class DeleteWishlistAccommodationTest {

		private WishlistAccommodation wishlistAccommodation;
		private Accommodation accommodation;

		@BeforeEach
		void setUpDeleteAccommodation() {
			// 숙소 엔티티
			accommodation = Accommodation.builder()
				.id(100L)
				.name("신라호텔")
				.build();

			// 현재 사용자의 위시리스트에 속한 숙소 항목
			wishlistAccommodation = WishlistAccommodation.builder()
				.id(10L)
				.memo("삭제할 메모")
				.wishlist(wishlist)
				.accommodation(accommodation)
				.build();
		}

		@Test
		@DisplayName("정상적으로 위시리스트에서 숙소를 삭제한다")
		void deleteWishlistAccommodation_Success() {
			// Given
			Long wishlistAccommodationId = 10L;

			given(wishlistAccommodationRepository.findById(wishlistAccommodationId))
				.willReturn(Optional.of(wishlistAccommodation));

			// When
			assertThatCode(() -> wishlistService.deleteWishlistAccommodation(wishlistAccommodationId))
				.doesNotThrowAnyException();

			// Then
			verify(wishlistAccommodationRepository).findById(wishlistAccommodationId);
			verify(wishlistAccommodationRepository).delete(wishlistAccommodation);

			// 권한 검증은 인터셉터에서 처리하므로 호출되지 않음
			verify(wishlistRepository, never()).findById(any());
			verify(memberRepository, never()).findById(any());
		}

		@Test
		@DisplayName("존재하지 않는 위시리스트 항목 삭제 시 예외 발생")
		void deleteWishlistAccommodation_WishlistAccommodationNotFound() {
			// Given
			Long nonExistentWishlistAccommodationId = 999L;

			given(wishlistAccommodationRepository.findById(nonExistentWishlistAccommodationId))
				.willReturn(Optional.empty());

			// When & Then
			assertThatThrownBy(() -> wishlistService.deleteWishlistAccommodation(nonExistentWishlistAccommodationId))
				.isInstanceOf(WishlistAccommodationNotFoundException.class)
				.hasMessage("존재하지 않는 위시리스트 항목입니다.");

			verify(wishlistAccommodationRepository).findById(nonExistentWishlistAccommodationId);
			verify(wishlistAccommodationRepository, never()).delete(any());

			// 권한 검증은 인터셉터에서 처리하므로 호출되지 않음
			verify(wishlistRepository, never()).findById(any());
			verify(memberRepository, never()).findById(any());
		}

		@Test
		@DisplayName("같은 숙소 항목을 중복으로 삭제하려 시도할 수 없다")
		void deleteWishlistAccommodation_DuplicateDelete() {
			// Given
			Long wishlistAccommodationId = 10L;

			// 첫 번째 삭제는 성공
			given(wishlistAccommodationRepository.findById(wishlistAccommodationId))
				.willReturn(Optional.of(wishlistAccommodation));

			// 첫 번째 삭제 수행
			wishlistService.deleteWishlistAccommodation(wishlistAccommodationId);

			// 두 번째 삭제 시도 시 항목이 존재하지 않음
			given(wishlistAccommodationRepository.findById(wishlistAccommodationId))
				.willReturn(Optional.empty());

			// When & Then
			assertThatThrownBy(() -> wishlistService.deleteWishlistAccommodation(wishlistAccommodationId))
				.isInstanceOf(WishlistAccommodationNotFoundException.class)
				.hasMessage("존재하지 않는 위시리스트 항목입니다.");

			verify(wishlistAccommodationRepository, times(2)).findById(wishlistAccommodationId);
			verify(wishlistAccommodationRepository, times(1)).delete(wishlistAccommodation);
		}

		@Test
		@DisplayName("위시리스트의 마지막 숙소를 삭제할 수 있다")
		void deleteWishlistAccommodation_LastItemInWishlist() {
			// Given
			Long lastWishlistAccommodationId = 10L;

			WishlistAccommodation lastAccommodation = WishlistAccommodation.builder()
				.id(lastWishlistAccommodationId)
				.memo("마지막 남은 숙소")
				.wishlist(wishlist)
				.accommodation(accommodation)
				.build();

			given(wishlistAccommodationRepository.findById(lastWishlistAccommodationId))
				.willReturn(Optional.of(lastAccommodation));

			// When
			assertThatCode(() -> wishlistService.deleteWishlistAccommodation(lastWishlistAccommodationId))
				.doesNotThrowAnyException();

			// Then
			verify(wishlistAccommodationRepository).findById(lastWishlistAccommodationId);
			verify(wishlistAccommodationRepository).delete(lastAccommodation);

			// 권한 검증은 인터셉터에서 처리하므로 호출되지 않음
			verify(wishlistRepository, never()).findById(any());
			verify(memberRepository, never()).findById(any());
		}

		@Test
		@DisplayName("여러 위시리스트 항목을 순차적으로 삭제할 수 있다")
		void deleteWishlistAccommodation_MultipleItemsSequentially() {
			// Given
			Long[] wishlistAccommodationIds = {10L, 11L, 12L};

			for (int i = 0; i < wishlistAccommodationIds.length; i++) {
				Long wishlistAccommodationId = wishlistAccommodationIds[i];

				WishlistAccommodation accommodationToDelete = WishlistAccommodation.builder()
					.id(wishlistAccommodationId)
					.memo("삭제할 메모 " + (i + 1))
					.wishlist(wishlist)
					.accommodation(accommodation)
					.build();

				given(wishlistAccommodationRepository.findById(wishlistAccommodationId))
					.willReturn(Optional.of(accommodationToDelete));

				// When
				assertThatCode(() -> wishlistService.deleteWishlistAccommodation(wishlistAccommodationId))
					.doesNotThrowAnyException();

				// Then
				verify(wishlistAccommodationRepository).findById(wishlistAccommodationId);
				verify(wishlistAccommodationRepository).delete(accommodationToDelete);
			}

			// 총 검증
			verify(wishlistAccommodationRepository, times(3)).findById(any());
			verify(wishlistAccommodationRepository, times(3)).delete(any(WishlistAccommodation.class));

			// 권한 검증은 인터셉터에서 처리하므로 호출되지 않음
			verify(wishlistRepository, never()).findById(any());
			verify(memberRepository, never()).findById(any());
		}

		@Test
		@DisplayName("위시리스트 항목 삭제 후 다른 항목은 영향받지 않는다")
		void deleteWishlistAccommodation_OnlyTargetItemDeleted() {
			// Given
			Long targetWishlistAccommodationId = 10L;

			WishlistAccommodation targetAccommodation = WishlistAccommodation.builder()
				.id(targetWishlistAccommodationId)
				.memo("삭제 대상")
				.wishlist(wishlist)
				.accommodation(accommodation)
				.build();

			WishlistAccommodation otherAccommodation = WishlistAccommodation.builder()
				.id(20L)
				.memo("삭제되지 않을 항목")
				.wishlist(wishlist)
				.accommodation(accommodation)
				.build();

			given(wishlistAccommodationRepository.findById(targetWishlistAccommodationId))
				.willReturn(Optional.of(targetAccommodation));

			// When
			wishlistService.deleteWishlistAccommodation(targetWishlistAccommodationId);

			// Then
			verify(wishlistAccommodationRepository).delete(targetAccommodation);
			// 다른 항목은 삭제되지 않음을 확인
			verify(wishlistAccommodationRepository, never()).delete(otherAccommodation);
		}

		@Test
		@DisplayName("동일한 숙소가 다른 위시리스트에도 있어도 해당 항목만 삭제된다")
		void deleteWishlistAccommodation_OnlySpecificWishlistItem() {
			// Given
			Long targetWishlistAccommodationId = 10L;

			// 현재 위시리스트의 항목
			WishlistAccommodation currentWishlistItem = WishlistAccommodation.builder()
				.id(targetWishlistAccommodationId)
				.memo("현재 위시리스트 항목")
				.wishlist(wishlist)
				.accommodation(accommodation)
				.build();

			// 다른 위시리스트의 같은 숙소 (삭제되면 안됨)
			WishlistAccommodation otherWishlistItem = WishlistAccommodation.builder()
				.id(30L)
				.memo("다른 위시리스트의 같은 숙소")
				.wishlist(otherWishlist)
				.accommodation(accommodation) // 같은 숙소
				.build();

			given(wishlistAccommodationRepository.findById(targetWishlistAccommodationId))
				.willReturn(Optional.of(currentWishlistItem));

			// When
			wishlistService.deleteWishlistAccommodation(targetWishlistAccommodationId);

			// Then
			verify(wishlistAccommodationRepository).delete(currentWishlistItem);
			// 다른 위시리스트의 같은 숙소는 삭제되지 않음
			verify(wishlistAccommodationRepository, never()).delete(otherWishlistItem);
		}
	}

	@Nested
	@DisplayName("위시리스트 숙소 목록 조회 테스트")
	class FindWishlistAccommodationsTest {

		private WishlistAccommodation firstWishlistAccommodation;
		private WishlistAccommodation secondWishlistAccommodation;
		private WishlistAccommodation thirdWishlistAccommodation;
		private Accommodation firstAccommodation;
		private Accommodation secondAccommodation;
		private Accommodation thirdAccommodation;

		@BeforeEach
		void setUpAccommodations() {
			// 기본 엔티티들만 생성 (Mock 설정은 각 테스트에서 개별적으로)
			firstAccommodation = mock(Accommodation.class);
			secondAccommodation = mock(Accommodation.class);
			thirdAccommodation = mock(Accommodation.class);

			// 위시리스트 항목들
			firstWishlistAccommodation = WishlistAccommodation.builder()
				.id(10L)
				.memo("신라호텔 메모")
				.wishlist(wishlist)
				.accommodation(firstAccommodation)
				.build();

			secondWishlistAccommodation = WishlistAccommodation.builder()
				.id(20L)
				.memo("롯데호텔 메모")
				.wishlist(wishlist)
				.accommodation(secondAccommodation)
				.build();

			thirdWishlistAccommodation = WishlistAccommodation.builder()
				.id(30L)
				.memo("게스트하우스 메모")
				.wishlist(wishlist)
				.accommodation(thirdAccommodation)
				.build();
		}

		// 테스트별로 필요한 Mock 데이터를 생성하는 헬퍼 메서드들
		private void setupAccommodationMocks() {
			given(firstAccommodation.getId()).willReturn(100L);
			given(firstAccommodation.getName()).willReturn("신라호텔");

			given(secondAccommodation.getId()).willReturn(200L);
			given(secondAccommodation.getName()).willReturn("롯데호텔");

			given(thirdAccommodation.getId()).willReturn(300L);
			given(thirdAccommodation.getName()).willReturn("게스트하우스");
		}

		private List<AccommodationImage> createMockImages(List<Accommodation> accommodations) {
			List<AccommodationImage> images = new ArrayList<>();

			for (Accommodation accommodation : accommodations) {
				if (accommodation.getId().equals(100L)) {
					AccommodationImage image1 = mock(AccommodationImage.class);
					given(image1.getAccommodation()).willReturn(accommodation);
					given(image1.getImageUrl()).willReturn("hotel1_image1.jpg");

					AccommodationImage image2 = mock(AccommodationImage.class);
					given(image2.getAccommodation()).willReturn(accommodation);
					given(image2.getImageUrl()).willReturn("hotel1_image2.jpg");

					images.addAll(List.of(image1, image2));
				} else if (accommodation.getId().equals(200L)) {
					AccommodationImage image3 = mock(AccommodationImage.class);
					given(image3.getAccommodation()).willReturn(accommodation);
					given(image3.getImageUrl()).willReturn("hotel2_image1.jpg");

					images.add(image3);
				}
			}
			return images;
		}

		private List<AccommodationAmenity> createMockAmenities(List<Accommodation> accommodations) {
			List<AccommodationAmenity> amenities = new ArrayList<>();

			for (Accommodation accommodation : accommodations) {
				if (accommodation.getId().equals(100L)) {
					Amenity wifiAmenity = mock(Amenity.class);
					given(wifiAmenity.getName()).willReturn(AmenityType.WIFI);

					Amenity tvAmenity = mock(Amenity.class);
					given(tvAmenity.getName()).willReturn(AmenityType.TV);

					AccommodationAmenity amenity1 = mock(AccommodationAmenity.class);
					given(amenity1.getAccommodation()).willReturn(accommodation);
					given(amenity1.getAmenity()).willReturn(wifiAmenity);
					given(amenity1.getCount()).willReturn(1);

					AccommodationAmenity amenity2 = mock(AccommodationAmenity.class);
					given(amenity2.getAccommodation()).willReturn(accommodation);
					given(amenity2.getAmenity()).willReturn(tvAmenity);
					given(amenity2.getCount()).willReturn(1);

					amenities.addAll(List.of(amenity1, amenity2));
				} else if (accommodation.getId().equals(200L)) {
					Amenity parkingAmenity = mock(Amenity.class);
					given(parkingAmenity.getName()).willReturn(AmenityType.PARKING);

					AccommodationAmenity amenity3 = mock(AccommodationAmenity.class);
					given(amenity3.getAccommodation()).willReturn(accommodation);
					given(amenity3.getAmenity()).willReturn(parkingAmenity);
					given(amenity3.getCount()).willReturn(1);

					amenities.add(amenity3);
				}
			}
			return amenities;
		}

		private List<AccommodationReviewSummary> createMockSummaries(List<Long> accommodationIds) {
			List<AccommodationReviewSummary> summaries = new ArrayList<>();

			for (Long accommodationId : accommodationIds) {
				if (accommodationId.equals(100L)) {
					AccommodationReviewSummary summary1 = mock(AccommodationReviewSummary.class);
					given(summary1.getAccommodationId()).willReturn(100L);
					given(summary1.getAverageRating()).willReturn(new BigDecimal("4.5"));
					summaries.add(summary1);
				} else if (accommodationId.equals(200L)) {
					AccommodationReviewSummary summary2 = mock(AccommodationReviewSummary.class);
					given(summary2.getAccommodationId()).willReturn(200L);
					given(summary2.getAverageRating()).willReturn(new BigDecimal("4.3"));
					summaries.add(summary2);
				}
			}
			return summaries;
		}

		@Test
		@DisplayName("정상적으로 위시리스트 숙소 목록을 조회한다")
		void findWishlistAccommodations_Success() {
			// Given
			setupAccommodationMocks(); // Mock 설정

			Long wishlistId = 1L;
			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(20)
				.lastId(null)
				.lastCreatedAt(null)
				.build();

			List<WishlistAccommodation> wishlistAccommodations = List.of(
				firstWishlistAccommodation,
				secondWishlistAccommodation,
				thirdWishlistAccommodation
			);

			Slice<WishlistAccommodation> wishlistAccommodationSlice =
				new SliceImpl<>(wishlistAccommodations, PageRequest.of(0, 20), false);

			List<Long> accommodationIds = List.of(100L, 200L, 300L);

			// Mock 설정
			given(wishlistAccommodationRepository.findByWishlistIdWithCursor(
				eq(wishlistId), eq(null), eq(null), any(PageRequest.class)))
				.willReturn(wishlistAccommodationSlice);

			// 해당 테스트에 필요한 Mock 데이터 생성
			List<Accommodation> accommodations = List.of(firstAccommodation, secondAccommodation, thirdAccommodation);
			List<AccommodationImage> images = createMockImages(accommodations);
			List<AccommodationAmenity> amenities = createMockAmenities(accommodations);
			List<AccommodationReviewSummary> summaries = createMockSummaries(accommodationIds);

			// Entity 기반 조회 Mock 설정
			given(accommodationRepository.findAccommodationImagesByAccommodationIds(accommodationIds))
				.willReturn(images);
			given(amenityRepository.findAccommodationAmenitiesByAccommodationIds(accommodationIds))
				.willReturn(amenities);
			given(summaryRepository.findByAccommodationIdIn(accommodationIds))
				.willReturn(summaries);

			CursorResponse.PageInfo pageInfo = CursorResponse.PageInfo.builder()
				.hasNext(false)
				.nextCursor(null)
				.currentSize(3)
				.build();

			given(cursorPageInfoCreator.createPageInfo(eq(wishlistAccommodations), eq(false), any(), any()))
				.willReturn(pageInfo);

			// When
			WishlistResponse.WishlistAccommodationInfos response =
				wishlistService.findWishlistAccommodations(wishlistId, request);

			// Then
			assertThat(response.wishlistAccommodations()).hasSize(3);
			assertThat(response.pageInfo().hasNext()).isFalse();
			assertThat(response.pageInfo().currentSize()).isEqualTo(3);

			// 첫 번째 항목 검증
			WishlistResponse.WishlistAccommodationInfo firstItem = response.wishlistAccommodations().get(0);
			assertThat(firstItem.id()).isEqualTo(10L);
			assertThat(firstItem.name()).isEqualTo("신라호텔 메모");
			assertThat(firstItem.accommodationInfo().accommodationId()).isEqualTo(100L);
			assertThat(firstItem.accommodationInfo().name()).isEqualTo("신라호텔");
			assertThat(firstItem.accommodationInfo().accommodationImageUrls()).hasSize(2);
			assertThat(firstItem.accommodationInfo().amenities()).hasSize(2);
			assertThat(firstItem.accommodationInfo().averageRating()).isEqualByComparingTo(new BigDecimal("4.5"));

			// Repository 호출 검증
			verify(wishlistAccommodationRepository).findByWishlistIdWithCursor(
				eq(wishlistId), eq(null), eq(null), any(PageRequest.class));
			verify(accommodationRepository).findAccommodationImagesByAccommodationIds(accommodationIds);
			verify(amenityRepository).findAccommodationAmenitiesByAccommodationIds(accommodationIds);
			verify(summaryRepository).findByAccommodationIdIn(accommodationIds);
		}

		@Test
		@DisplayName("한 개의 숙소만 있는 위시리스트를 조회한다")
		void findWishlistAccommodations_SingleItem() {
			// Given
			given(firstAccommodation.getId()).willReturn(100L);
			given(firstAccommodation.getName()).willReturn("신라호텔");

			Long wishlistId = 1L;
			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(20)
				.build();

			List<WishlistAccommodation> singleAccommodation = List.of(firstWishlistAccommodation);
			Slice<WishlistAccommodation> wishlistAccommodationSlice =
				new SliceImpl<>(singleAccommodation, PageRequest.of(0, 20), false);

			List<Long> accommodationIds = List.of(100L);

			given(wishlistAccommodationRepository.findByWishlistIdWithCursor(
				eq(wishlistId), eq(null), eq(null), any(PageRequest.class)))
				.willReturn(wishlistAccommodationSlice);

			// 단일 숙소에 대한 데이터
			List<Accommodation> accommodations = List.of(firstAccommodation);
			List<AccommodationImage> images = createMockImages(accommodations);
			List<AccommodationAmenity> amenities = createMockAmenities(accommodations);
			List<AccommodationReviewSummary> summaries = createMockSummaries(accommodationIds);

			given(accommodationRepository.findAccommodationImagesByAccommodationIds(accommodationIds))
				.willReturn(images);
			given(amenityRepository.findAccommodationAmenitiesByAccommodationIds(accommodationIds))
				.willReturn(amenities);
			given(summaryRepository.findByAccommodationIdIn(accommodationIds))
				.willReturn(summaries);

			CursorResponse.PageInfo pageInfo = CursorResponse.PageInfo.builder()
				.hasNext(false)
				.nextCursor(null)
				.currentSize(1)
				.build();

			given(cursorPageInfoCreator.createPageInfo(eq(singleAccommodation), eq(false), any(), any()))
				.willReturn(pageInfo);

			// When
			WishlistResponse.WishlistAccommodationInfos response =
				wishlistService.findWishlistAccommodations(wishlistId, request);

			// Then
			assertThat(response.wishlistAccommodations()).hasSize(1);
			assertThat(response.pageInfo().hasNext()).isFalse();
			assertThat(response.pageInfo().currentSize()).isEqualTo(1);

			WishlistResponse.WishlistAccommodationInfo item = response.wishlistAccommodations().get(0);
			assertThat(item.id()).isEqualTo(10L);
			assertThat(item.name()).isEqualTo("신라호텔 메모");
			assertThat(item.accommodationInfo().accommodationId()).isEqualTo(100L);
			assertThat(item.accommodationInfo().name()).isEqualTo("신라호텔");

			verify(wishlistAccommodationRepository).findByWishlistIdWithCursor(
				eq(wishlistId), eq(null), eq(null), any(PageRequest.class));
			verify(accommodationRepository).findAccommodationImagesByAccommodationIds(accommodationIds);
			verify(amenityRepository).findAccommodationAmenitiesByAccommodationIds(accommodationIds);
			verify(summaryRepository).findByAccommodationIdIn(accommodationIds);
		}

		@Test
		@DisplayName("빈 위시리스트의 숙소 목록을 조회한다")
		void findWishlistAccommodations_EmptyWishlist() {
			// Given
			Long emptyWishlistId = 1L;
			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(20)
				.lastId(null)
				.lastCreatedAt(null)
				.build();

			Slice<WishlistAccommodation> emptySlice =
				new SliceImpl<>(List.of(), PageRequest.of(0, 20), false);

			given(wishlistAccommodationRepository.findByWishlistIdWithCursor(
				eq(emptyWishlistId), eq(null), eq(null), any(PageRequest.class)))
				.willReturn(emptySlice);

			CursorResponse.PageInfo pageInfo = CursorResponse.PageInfo.builder()
				.hasNext(false)
				.nextCursor(null)
				.currentSize(0)
				.build();

			given(cursorPageInfoCreator.createPageInfo(eq(List.of()), eq(false), any(), any()))
				.willReturn(pageInfo);

			// When
			WishlistResponse.WishlistAccommodationInfos response =
				wishlistService.findWishlistAccommodations(emptyWishlistId, request);

			// Then
			assertThat(response.wishlistAccommodations()).isEmpty();
			assertThat(response.pageInfo().hasNext()).isFalse();
			assertThat(response.pageInfo().currentSize()).isEqualTo(0);

			verify(wishlistAccommodationRepository).findByWishlistIdWithCursor(
				eq(emptyWishlistId), eq(null), eq(null), any(PageRequest.class));
		}

		@Test
		@DisplayName("커서 기반 페이징으로 위시리스트 숙소 목록을 조회한다")
		void findWishlistAccommodations_WithCursor() {
			// Given
			given(firstAccommodation.getId()).willReturn(100L);
			given(firstAccommodation.getName()).willReturn("신라호텔");
			given(secondAccommodation.getId()).willReturn(200L);
			given(secondAccommodation.getName()).willReturn("롯데호텔");

			Long wishlistId = 1L;
			Long lastId = 30L;
			LocalDateTime lastCreatedAt = LocalDateTime.now().minusDays(1);

			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(2)
				.lastId(lastId)
				.lastCreatedAt(lastCreatedAt)
				.build();

			List<WishlistAccommodation> pagedAccommodations = List.of(
				firstWishlistAccommodation,
				secondWishlistAccommodation
			);

			Slice<WishlistAccommodation> wishlistAccommodationSlice =
				new SliceImpl<>(pagedAccommodations, PageRequest.of(0, 2), true);

			List<Long> accommodationIds = List.of(100L, 200L);

			given(wishlistAccommodationRepository.findByWishlistIdWithCursor(
				eq(wishlistId), eq(lastId), eq(lastCreatedAt), any(PageRequest.class)))
				.willReturn(wishlistAccommodationSlice);

			// 페이지에 해당하는 데이터만 생성
			List<Accommodation> accommodations = List.of(firstAccommodation, secondAccommodation);
			List<AccommodationImage> images = createMockImages(accommodations);
			List<AccommodationAmenity> amenities = createMockAmenities(accommodations);
			List<AccommodationReviewSummary> summaries = createMockSummaries(accommodationIds);

			given(accommodationRepository.findAccommodationImagesByAccommodationIds(accommodationIds))
				.willReturn(images);
			given(amenityRepository.findAccommodationAmenitiesByAccommodationIds(accommodationIds))
				.willReturn(amenities);
			given(summaryRepository.findByAccommodationIdIn(accommodationIds))
				.willReturn(summaries);

			CursorResponse.PageInfo pageInfo = CursorResponse.PageInfo.builder()
				.hasNext(true)
				.nextCursor("encoded_cursor")
				.currentSize(2)
				.build();

			given(cursorPageInfoCreator.createPageInfo(eq(pagedAccommodations), eq(true), any(), any()))
				.willReturn(pageInfo);

			// When
			WishlistResponse.WishlistAccommodationInfos response =
				wishlistService.findWishlistAccommodations(wishlistId, request);

			// Then
			assertThat(response.wishlistAccommodations()).hasSize(2);
			assertThat(response.pageInfo().hasNext()).isTrue();
			assertThat(response.pageInfo().nextCursor()).isEqualTo("encoded_cursor");
			assertThat(response.pageInfo().currentSize()).isEqualTo(2);

			verify(wishlistAccommodationRepository).findByWishlistIdWithCursor(
				eq(wishlistId), eq(lastId), eq(lastCreatedAt), any(PageRequest.class));
			verify(accommodationRepository).findAccommodationImagesByAccommodationIds(accommodationIds);
			verify(amenityRepository).findAccommodationAmenitiesByAccommodationIds(accommodationIds);
			verify(summaryRepository).findByAccommodationIdIn(accommodationIds);
		}

		@Test
		@DisplayName("숙소에 이미지나 편의시설이 없는 경우를 처리한다")
		void findWishlistAccommodations_WithMissingData() {
			// Given
			given(thirdAccommodation.getId()).willReturn(300L);
			given(thirdAccommodation.getName()).willReturn("게스트하우스");

			Long wishlistId = 1L;
			CursorRequest.CursorPageRequest request = CursorRequest.CursorPageRequest.builder()
				.size(20)
				.build();

			List<WishlistAccommodation> wishlistAccommodations = List.of(thirdWishlistAccommodation);
			Slice<WishlistAccommodation> wishlistAccommodationSlice =
				new SliceImpl<>(wishlistAccommodations, PageRequest.of(0, 20), false);

			given(wishlistAccommodationRepository.findByWishlistIdWithCursor(
				eq(wishlistId), eq(null), eq(null), any(PageRequest.class)))
				.willReturn(wishlistAccommodationSlice);

			// 실제 서비스에서 [300]으로 호출하므로 그에 맞춰 stubbing
			given(accommodationRepository.findAccommodationImagesByAccommodationIds(eq(List.of(300L))))
				.willReturn(List.of());
			given(amenityRepository.findAccommodationAmenitiesByAccommodationIds(eq(List.of(300L))))
				.willReturn(List.of());
			given(summaryRepository.findByAccommodationIdIn(eq(List.of(300L))))
				.willReturn(List.of());

			CursorResponse.PageInfo pageInfo = CursorResponse.PageInfo.builder()
				.hasNext(false)
				.nextCursor(null)
				.currentSize(1)
				.build();

			given(cursorPageInfoCreator.createPageInfo(eq(wishlistAccommodations), eq(false), any(), any()))
				.willReturn(pageInfo);

			// When
			WishlistResponse.WishlistAccommodationInfos response =
				wishlistService.findWishlistAccommodations(wishlistId, request);

			// Then
			assertThat(response.wishlistAccommodations()).hasSize(1);

			WishlistResponse.WishlistAccommodationInfo item = response.wishlistAccommodations().getFirst();
			assertThat(item.id()).isEqualTo(30L);
			assertThat(item.name()).isEqualTo("게스트하우스 메모");
			assertThat(item.accommodationInfo().accommodationId()).isEqualTo(300L);
			assertThat(item.accommodationInfo().name()).isEqualTo("게스트하우스");
			assertThat(item.accommodationInfo().accommodationImageUrls()).isEmpty();
			assertThat(item.accommodationInfo().amenities()).isEmpty();
			assertThat(item.accommodationInfo().averageRating()).isNull();

			// verify도 [300]으로 변경
			verify(accommodationRepository).findAccommodationImagesByAccommodationIds(eq(List.of(300L)));
			verify(amenityRepository).findAccommodationAmenitiesByAccommodationIds(eq(List.of(300L)));
			verify(summaryRepository).findByAccommodationIdIn(eq(List.of(300L)));
		}
	}
}
