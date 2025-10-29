package kr.kro.airbob.domain.wishlist.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import kr.kro.airbob.domain.wishlist.entity.WishlistAccommodation;
import kr.kro.airbob.domain.wishlist.repository.querydsl.WishlistAccommodationRepositoryCustom;

@Repository
public interface WishlistAccommodationRepository extends JpaRepository<WishlistAccommodation, Long>,
	WishlistAccommodationRepositoryCustom {

	Optional<WishlistAccommodation> findByIdAndWishlistMemberId(Long id, Long memberId);

	void deleteAllByWishlistId(Long wishlistId);

	@Query("""
		SELECT 
			wa.wishlist.id,
			COUNT(wa)
		FROM WishlistAccommodation  wa
		WHERE wa.wishlist.id IN :wishlistIds
		GROUP BY wa.wishlist.id
""")
	Map<Long, Long> countByWishlistIds(@Param("wishlistIds") List<Long> wishlistIds);

	@Query(value = """
		SELECT 
			wishlist_id,
			thumbnail_url
		FROM (
			SELECT 
				wa.wishlist_id,
				a.thumbnail_url,
				ROW_NUMBER() OVER (
					PARTITION BY wa.wishlist_id ORDER BY wa.created_at DESC
				) as rn
			FROM wishlist_accommodation wa
			JOIN accommodation a ON wa.accommodation_id = a.id
			WHERE wa.wishlist_id IN :wishlistIds
		) ranked
		WHERE rn = 1
""", nativeQuery = true)
	Map<Long, String> findLatestThumbnailUrlsByWishlistIds(@Param("wishlistIds") List<Long> wishlistIds);

	boolean existsByWishlistIdAndAccommodationId(Long wishlistId, Long accommodationId);
	boolean existsByWishlist_Member_IdAndAccommodation_Id(Long memberId, Long accommodationId);


	@Query("""
	SELECT 
		wa.accommodation.id
	FROM WishlistAccommodation wa 
	WHERE wa.wishlist.member.id = :memberId
	AND wa.accommodation.id IN :accommodationIds
	""")
	Set<Long> findAccommodationIdsByMemberIdAndAccommodationIds(
		@Param("memberId") Long memberId,
		@Param("accommodationIds") List<Long> accommodationIds);
}

