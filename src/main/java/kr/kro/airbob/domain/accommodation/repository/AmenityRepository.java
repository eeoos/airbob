package kr.kro.airbob.domain.accommodation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.kro.airbob.domain.accommodation.common.AmenityType;
import kr.kro.airbob.domain.accommodation.entity.Amenity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AmenityRepository extends JpaRepository<Amenity, Long> {
    List<Amenity> findByNameIn(Collection<AmenityType> names);
}
