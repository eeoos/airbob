package kr.kro.airbob.domain.accommodation.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;

public interface AccommodationAmenityRepository extends JpaRepository<AccommodationAmenity, Long> {
    void deleteAllByAccommodationId(Long accommodationId);

    List<AccommodationAmenity> findAllByAccommodationId(Long accommodationId);
    List<AccommodationAmenity> findAllByAccommodation_AccommodationUid(UUID accommodationUid);

    void deleteByAccommodationId(Long accommodationId);

    boolean existsByAccommodationId(Long accommodationId);

	@Query("""
    SELECT 
    	aa
    FROM AccommodationAmenity aa
    WHERE aa.accommodation.id IN :accommodationIds
    ORDER BY aa.accommodation.id, aa.amenity.name
    """)
	List<AccommodationAmenity> findAccommodationAmenitiesByAccommodationIds(
		@Param("accommodationIds") List<Long> accommodationIds);
}
