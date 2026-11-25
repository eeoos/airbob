package kr.kro.airbob.core.domain.accommodation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.core.domain.accommodation.Address;

public interface AddressRepository extends JpaRepository<Address, Long> {}
