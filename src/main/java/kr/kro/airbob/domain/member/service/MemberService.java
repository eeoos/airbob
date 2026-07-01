package kr.kro.airbob.domain.member.service;

import java.time.LocalDateTime;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.dto.MemberRequest.Signup;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.entity.MemberHistory;
import kr.kro.airbob.domain.member.exception.DuplicatedEmailException;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.repository.MemberHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final MemberHistoryRepository historyRepository;

    @Transactional
    public void createMember(Signup request) {
        if(memberRepository.existsByEmailAndStatus(request.getEmail(), MemberStatus.ACTIVE)){
            throw new DuplicatedEmailException();
        }

        String hashedPassword = BCrypt.hashpw(request.getPassword(), BCrypt.gensalt());
        Member member = Member.createMember(request, hashedPassword);
        memberRepository.save(member);

        // 첫 데이터(CREATE)부터 이력 기록 — 가입은 비인증 컨텍스트라 source_system 명시
        historyRepository.save(MemberHistory.openSystem(member, ChangeType.CREATE, "신규 회원가입", "API"));
    }

    @Transactional
    public void deleteMember(Long memberId, String reason) {
        Member member = memberRepository.findById(memberId)
            .orElseThrow(MemberNotFoundException::new);

        member.delete();
        memberRepository.save(member);

        // SCD2: 직전 현재 행을 닫고 새 스냅샷을 연다
        historyRepository.findByMemberIdAndValidTo(member.getId(), HistoryConstants.FOREVER)
            .ifPresent(current -> current.close(LocalDateTime.now()));
        historyRepository.save(MemberHistory.open(member, ChangeType.DELETE, reason));
    }
}
