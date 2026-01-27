package kr.kro.airbob.domain.accommodation.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.image.entity.AccommodationImage;

public interface AccommodationImageRepository extends JpaRepository<AccommodationImage, Long> {

	List<AccommodationImage> findByAccommodation_AccommodationUidOrderByIdAsc(UUID accommodationUid);

	long countByAccommodationId(Long accommodationId);

	List<AccommodationImage> findByAccommodationIdOrderByIdAsc(Long accommodationId);

}
