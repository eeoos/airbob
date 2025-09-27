package kr.kro.airbob.domain.accommodation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.repository.querydsl.AccommodationRepositoryCustom;
import kr.kro.airbob.domain.image.AccommodationImage;

public interface AccommodationRepository extends JpaRepository<Accommodation, Long>, AccommodationRepositoryCustom {

	Optional<Accommodation> findByAccommodationUid(UUID accommodationUid);

	Optional<Address> findAddressById(Long accommodationId);

	@Query("select a.member.id from Accommodation a where a.id = :id")
	Optional<Long> findHostIdByAccommodationId(Long id);

	@Query("""
		SELECT 
			ai
		FROM AccommodationImage ai
		WHERE ai.accommodation.id IN :accommodationIds
		ORDER BY ai.accommodation.id
		""")
	List<AccommodationImage> findAccommodationImagesByAccommodationIds(
		@Param("accommodationIds") List<Long> accommodationIds);

	List<Accommodation> findByIdIn(List<Long> accommodationIds);

	@EntityGraph(attributePaths = {
		"address",
		"occupancyPolicy",
		"member",
		"accommodationAmenities.amenity",
		"accommodationImages"
	})
	@Query("SELECT a FROM Accommodation a WHERE a.id = :accommodationId")
	Optional<Accommodation> findByIdAllRelations(@Param("accommodationId") Long accommodationId);

	@Query("""
    SELECT ai
    FROM AccommodationImage ai
    WHERE ai.accommodation.id = :accommodationId
    ORDER BY ai.accommodation.id
    """)
	List<AccommodationImage> findImagesByAccommodationId(@Param("accommodationId") Long accommodationId);
}
