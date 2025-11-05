package kr.kro.airbob.domain.accommodation.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;

public interface AccommodationAmenityRepository extends JpaRepository<AccommodationAmenity, Long> {

    List<AccommodationAmenity> findAllByAccommodationId(Long accommodationId);
    List<AccommodationAmenity> findAllByAccommodation_AccommodationUid(UUID accommodationUid);

    void deleteByAccommodationId(Long accommodationId);
}
