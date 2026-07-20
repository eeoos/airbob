package kr.kro.airbob.domain.coupon.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import kr.kro.airbob.domain.coupon.entity.Coupon;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

	List<Coupon> findByIsActiveTrue();

	/**
	 * Lua 운영 경로로 전환할 수 없는 기존 Redisson 발급 쿠폰 수를 조회한다.
	 * 준비 서비스와 같은 기준으로 발급 수와 실제 회원 쿠폰 행을 모두 확인한다.
	 */
	@Query("""
		select count(c)
		from Coupon c
		where c.isActive = true
		  and c.redisStockPreparedAt is null
		  and (
		    c.issuedQuantity > 0
		    or exists (
		      select mc.id
		      from MemberCoupon mc
		      where mc.coupon = c
		    )
		  )
		""")
	long countActiveUnpreparedCouponsWithLegacyIssuances();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Coupon c where c.id = :id")
	Optional<Coupon> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 발급 수를 DB 레벨에서 원자적으로 증가시킨다.
	 * Lua 경로에서 재고는 Redis 가 통제하므로, DB issuedQuantity 는
	 * read-modify-write 대신 이 단일 UPDATE 로 정확히 누적한다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("update Coupon c set c.issuedQuantity = c.issuedQuantity + 1 where c.id = :id")
	int incrementIssuedQuantity(@Param("id") Long id);
}
