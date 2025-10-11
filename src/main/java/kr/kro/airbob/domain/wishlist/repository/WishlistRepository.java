package kr.kro.airbob.domain.wishlist.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import kr.kro.airbob.domain.wishlist.entity.Wishlist;
import kr.kro.airbob.domain.wishlist.entity.WishlistStatus;
import kr.kro.airbob.domain.wishlist.repository.querydsl.WishlistRepositoryCustom;

@Repository
public interface WishlistRepository extends JpaRepository<Wishlist, Long>, WishlistRepositoryCustom {

	boolean existsByIdAndMemberId(Long id, Long memberId);
}
