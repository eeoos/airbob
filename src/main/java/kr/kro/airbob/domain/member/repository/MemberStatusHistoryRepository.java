package kr.kro.airbob.domain.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.member.entity.MemberStatusHistory;

public interface MemberStatusHistoryRepository extends JpaRepository<MemberStatusHistory, Long> {

}
