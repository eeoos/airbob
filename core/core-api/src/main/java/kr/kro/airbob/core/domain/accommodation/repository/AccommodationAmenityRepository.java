package kr.kro.airbob.core.domain.accommodation.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.core.domain.accommodation.AccommodationAmenity;
import kr.kro.airbob.core.domain.accommodation.repository.projection.AccommodationAmenityProjection;
import kr.kro.airbob.core.domain.accommodation.repository.querydsl.AccommodationAmenityRepositoryCustom;

public interface AccommodationAmenityRepository extends JpaRepository<AccommodationAmenity, Long>,
	AccommodationAmenityRepositoryCustom {

	List<AccommodationAmenity> findAllByAccommodationId(Long accommodationId);

	@Query("""
		select new kr.kro.airbob.core.domain.accommodation.repository.projection.AccommodationAmenityProjection(
		    amenity.amenityCode, amenity.count)
		from AccommodationAmenity amenity where amenity.accommodation.id = :accommodationId
		""")
	List<AccommodationAmenityProjection> findDetailAmenitiesByAccommodationId(
		@Param("accommodationId")
		Long accommodationId);

	List<AccommodationAmenity> findAllByAccommodationAccommodationUid(UUID accommodationUid);

	@Modifying
	@Query("DELETE FROM AccommodationAmenity amenity WHERE amenity.accommodation.id = :accommodationId")
	int deleteByAccommodationIdInBulk(
		@Param("accommodationId")
		Long accommodationId);
}
