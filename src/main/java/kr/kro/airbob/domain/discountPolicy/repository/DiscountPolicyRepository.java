package kr.kro.airbob.domain.discountPolicy.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import kr.kro.airbob.domain.discountPolicy.entity.DiscountPolicy;

@Repository
public interface DiscountPolicyRepository extends JpaRepository<DiscountPolicy, Long> {

    List<DiscountPolicy> findByIsActiveTrue();

}
