package kr.kro.airbob.domain.reservation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import kr.kro.airbob.domain.reservation.entity.ReservationQuote;

public interface ReservationQuoteRepository extends JpaRepository<ReservationQuote, Long> {

	Optional<ReservationQuote> findByQuoteUid(UUID quoteUid);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select quote from ReservationQuote quote
		where quote.quoteUid = :quoteUid and quote.memberId = :memberId
		""")
	Optional<ReservationQuote> findByQuoteUidAndMemberIdForUpdate(
		@Param("quoteUid") UUID quoteUid,
		@Param("memberId") Long memberId
	);

	@Query(value = """
		select id
		from reservation_quote force index (idx_reservation_quote_cleanup)
		where created_at < :cutoffExclusive
		order by created_at, id
		limit :batchSize
		for update skip locked
		""", nativeQuery = true)
	List<Long> findExpiredIdsForCleanup(
		@Param("cutoffExclusive") Instant cutoffExclusive,
		@Param("batchSize") int batchSize
	);

	@Modifying
	// Keep this as a direct primary-key DELETE. JpaRepository's JPQL batch delete
	// waited on a row already skipped by the concurrent MySQL locking read.
	@Query(value = "delete from reservation_quote where id in (:ids)", nativeQuery = true)
	int deleteCleanupBatchByIds(@Param("ids") List<Long> ids);
}
