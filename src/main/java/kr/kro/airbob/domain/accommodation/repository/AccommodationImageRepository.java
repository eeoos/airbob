package kr.kro.airbob.domain.accommodation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.image.entity.AccommodationImage;

public interface AccommodationImageRepository extends JpaRepository<AccommodationImage, Long> {

	long countByAccommodationId(Long accommodationId);

	List<AccommodationImage> findByAccommodationIdOrderByIdAsc(Long accommodationId);

}
