package kr.kro.airbob.domain.accommodation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.image.entity.AccommodationImage;

public interface AccommodationImageRepository extends JpaRepository<AccommodationImage, Long> {

	long countByAccommodationId(Long accommodationId);

	@Query("""
		SELECT image
		FROM AccommodationImage image
		WHERE image.accommodation.id = :accommodationId
		ORDER BY image.id ASC
		""")
	List<AccommodationImage> findByAccommodationIdOrderByIdAsc(
		@Param("accommodationId") Long accommodationId
	);

}
