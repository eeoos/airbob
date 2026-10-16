package kr.kro.airbob.domain.wishlist.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import kr.kro.airbob.domain.wishlist.entity.WishlistAccommodation;
import kr.kro.airbob.domain.wishlist.repository.querydsl.WishlistAccommodationRepositoryCustom;

@Repository
public interface WishlistAccommodationRepository extends JpaRepository<WishlistAccommodation, Long>,
	WishlistAccommodationRepositoryCustom {

	interface MembershipProjection {
		Long getAnyMembership();
		Long getTargetWishlist();
		Long getTargetMembership();
	}

	@Query(value = """
		SELECT
			CAST(EXISTS (
				SELECT 1 FROM wishlist_accommodation wa
				JOIN wishlist w ON w.id = wa.wishlist_id
				WHERE w.member_id = :memberId AND w.status = 'ACTIVE'
					AND wa.accommodation_id = :accommodationId
			) AS SIGNED) AS anyMembership,
			CAST(EXISTS (
				SELECT 1 FROM wishlist w
				WHERE w.id = :wishlistId AND w.member_id = :memberId AND w.status = 'ACTIVE'
			) AS SIGNED) AS targetWishlist,
			CAST(EXISTS (
				SELECT 1 FROM wishlist_accommodation wa
				JOIN wishlist w ON w.id = wa.wishlist_id
				WHERE w.id = :wishlistId AND w.member_id = :memberId AND w.status = 'ACTIVE'
					AND wa.accommodation_id = :accommodationId
			) AS SIGNED) AS targetMembership
		""", nativeQuery = true)
	MembershipProjection findMembership(@Param("memberId") Long memberId,
		@Param("accommodationId") Long accommodationId, @Param("wishlistId") Long wishlistId);

	Optional<WishlistAccommodation> findByIdAndWishlistMemberId(Long id, Long memberId);
	List<WishlistAccommodation> findAllByWishlistId(Long wishlistId);
	boolean existsByWishlistIdAndAccommodationId(Long wishlistId, Long accommodationId);
	boolean existsByWishlist_Member_IdAndAccommodation_Id(Long memberId, Long accommodationId);
}
