package kr.kro.airbob.domain.accommodation.repository;

import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OccupancyPolicyRepository extends JpaRepository<OccupancyPolicy, Long> {
}
