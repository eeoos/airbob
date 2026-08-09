package kr.kro.airbob.domain.member.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.entity.MemberStatus;

public interface MemberRepository extends JpaRepository<Member, Long> {
    boolean existsByEmailAndStatus(String email, MemberStatus status);

    Optional<Member> findByEmailAndStatus(String email, MemberStatus status);

    Optional<Member> findByIdAndStatus(Long id, MemberStatus status);

    boolean existsByIdAndStatusAndRole(Long id, MemberStatus status, MemberRole role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :id AND m.status = :status")
    Optional<Member> findByIdAndStatusForUpdate(
        @Param("id") Long id,
        @Param("status") MemberStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id IN :ids ORDER BY m.id")
    List<Member> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

}
