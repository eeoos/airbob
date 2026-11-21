package kr.kro.airbob.domain.coupon.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.repository.querydsl.CouponRepositoryCustom;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long>, CouponRepositoryCustom {

	@Query(value = "select utc_timestamp(6)", nativeQuery = true)
	LocalDateTime findDatabaseUtcNow();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Coupon c where c.id = :id")
	Optional<Coupon> findByIdForUpdate(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select c from Coupon c where c.id = :id")
	Optional<Coupon> findByIdForShare(@Param("id") Long id);

	@Query(value = "select * from coupon where id = :id for share nowait", nativeQuery = true)
	Optional<Coupon> findByIdForShareNowait(@Param("id") Long id);

}
