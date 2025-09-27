package kr.kro.airbob.domain.accommodation.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.image.AccommodationImage;

public interface AccommodationImageRepository extends JpaRepository<AccommodationImage, Long> {
	@Query("""
    SELECT ai
    FROM AccommodationImage ai
    WHERE ai.accommodation.id = :accommodationId
    ORDER BY ai.accommodation.id
    """)
	List<AccommodationImage> findImagesByAccommodationId(@Param("accommodationId") Long accommodationId);

	@Query("""
    SELECT ai
    FROM AccommodationImage ai
    WHERE ai.accommodation.accommodationUid = :accommodationUid
    ORDER BY ai.accommodation.id
    """)
	List<AccommodationImage> findImagesByAccommodationUid(@Param("accommodationUid") UUID accommodationUid);

	@Query("""
		SELECT 
			ai
		FROM AccommodationImage ai
		WHERE ai.accommodation.id IN :accommodationIds
		ORDER BY ai.accommodation.id
		""")
	List<AccommodationImage> findAccommodationImagesByAccommodationIds(
		@Param("accommodationIds") List<Long> accommodationIds);
}
