package kr.kro.airbob.domain.accommodation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.querydsl.AccommodationRepositoryCustom;

public interface AccommodationRepository extends JpaRepository<Accommodation, Long>, AccommodationRepositoryCustom {

	Optional<Accommodation> findByIdAndStatus(Long id, AccommodationStatus status);

	List<Accommodation> findByIdInAndStatus(List<Long> accommodationIds, AccommodationStatus status);

	Optional<Accommodation> findByIdAndMemberId(Long accommodationId, Long memberId);

	Optional<Accommodation> findByIdAndMemberIdAndStatusNot(Long accommodationId, Long memberId, AccommodationStatus accommodationStatus);

}
