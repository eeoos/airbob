package kr.kro.airbob.core.domain.accommodation.repository;

import kr.kro.airbob.core.domain.accommodation.OccupancyPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OccupancyPolicyRepository extends JpaRepository<OccupancyPolicy, Long> {
}
