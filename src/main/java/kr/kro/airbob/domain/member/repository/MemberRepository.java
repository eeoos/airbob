package kr.kro.airbob.domain.member.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;

public interface MemberRepository extends JpaRepository<Member, Long> {
    boolean existsByEmailAndStatus(String email, MemberStatus status);

    Optional<Member> findByEmailAndStatus(String email, MemberStatus status);
}
