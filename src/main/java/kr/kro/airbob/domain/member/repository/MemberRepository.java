package kr.kro.airbob.domain.member.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.member.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {
    boolean existsByEmail(String email);

    Optional<Member> findByEmail(String email);
}
