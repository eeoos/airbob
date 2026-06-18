package kr.kro.airbob.domain.accommodation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.querydsl.AccommodationRepositoryCustom;

public interface AccommodationRepository extends JpaRepository<Accommodation, Long>, AccommodationRepositoryCustom {

	// 대표 숙소 id 묶음 → 썸네일 URL 배치 조회 (위시리스트 목록 반정규화 읽기용)
	interface ThumbnailUrlProjection {
		Long getId();
		String getThumbnailUrl();
	}

	@Query("SELECT a.id AS id, a.thumbnailUrl AS thumbnailUrl FROM Accommodation a WHERE a.id IN :ids")
	List<ThumbnailUrlProjection> findThumbnailUrlsByIds(@Param("ids") Collection<Long> ids);

	Optional<Accommodation> findByIdAndStatus(Long id, AccommodationStatus status);
	Optional<Accommodation> findByIdAndMemberIdAndStatus(Long id, Long memberId, AccommodationStatus status);

	List<Accommodation> findByIdInAndStatus(List<Long> accommodationIds, AccommodationStatus status);

	Optional<Accommodation> findByIdAndMemberId(Long accommodationId, Long memberId);

	Optional<Accommodation> findByIdAndMemberIdAndStatusNot(Long accommodationId, Long memberId, AccommodationStatus accommodationStatus);

}
