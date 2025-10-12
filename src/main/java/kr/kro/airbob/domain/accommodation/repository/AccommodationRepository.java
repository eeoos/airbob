package kr.kro.airbob.domain.accommodation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.repository.querydsl.AccommodationRepositoryCustom;
import kr.kro.airbob.domain.image.AccommodationImage;

public interface AccommodationRepository extends JpaRepository<Accommodation, Long>, AccommodationRepositoryCustom {

	Optional<Accommodation> findByIdAndStatus(Long id, AccommodationStatus status);

	List<Accommodation> findByIdInAndStatus(List<Long> accommodationIds, AccommodationStatus status);

	@Query("select a.member.id from Accommodation a where a.id = :id")
	Optional<Long> findHostIdByAccommodationId(Long id);

}
