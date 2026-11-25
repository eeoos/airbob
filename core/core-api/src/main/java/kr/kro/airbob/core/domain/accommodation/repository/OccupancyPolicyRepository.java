package kr.kro.airbob.core.domain.accommodation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.core.domain.accommodation.OccupancyPolicy;

public interface OccupancyPolicyRepository extends JpaRepository<OccupancyPolicy, Long> {}
