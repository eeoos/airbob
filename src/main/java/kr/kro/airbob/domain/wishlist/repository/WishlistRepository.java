package kr.kro.airbob.domain.wishlist.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import kr.kro.airbob.domain.wishlist.entity.Wishlist;
import kr.kro.airbob.domain.wishlist.entity.WishlistStatus;
import kr.kro.airbob.domain.wishlist.repository.projection.WishlistDetailHeaderProjection;
import kr.kro.airbob.domain.wishlist.repository.querydsl.WishlistRepositoryCustom;

@Repository
public interface WishlistRepository extends JpaRepository<Wishlist, Long>, WishlistRepositoryCustom {

	boolean existsByIdAndMemberId(Long id, Long memberId);

	Optional<Wishlist> findByIdAndStatus(Long id, WishlistStatus status);

	@Query("""
		select new kr.kro.airbob.domain.wishlist.repository.projection.WishlistDetailHeaderProjection(
			w.name, w.member.id)
		from Wishlist w
		where w.id = :id and w.status = :status
		""")
	Optional<WishlistDetailHeaderProjection> findDetailHeaderByIdAndStatus(
		@Param("id") Long id, @Param("status") WishlistStatus status);

	// 반정규화 카운터 원자적 유지(증가/감소/대표 재선정)는 WishlistRepositoryCustom(QueryDSL)에서 제공.
}
