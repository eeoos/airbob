package kr.kro.airbob.domain.member.service;

import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.dto.MemberRequestDto.SignupMemberRequestDto;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.entity.MemberStatusHistory;
import kr.kro.airbob.domain.member.exception.DuplicatedEmailException;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.repository.MemberStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final MemberStatusHistoryRepository historyRepository;

    @Transactional
    public void createMember(SignupMemberRequestDto request) {
        if(memberRepository.existsByEmailAndStatus(request.getEmail(), MemberStatus.ACTIVE)){
            throw new DuplicatedEmailException();
        }

        String hashedPassword = BCrypt.hashpw(request.getPassword(), BCrypt.gensalt());

        Member member = Member.createMember(request, hashedPassword);
        memberRepository.save(member);

        MemberStatusHistory history = MemberStatusHistory.builder()
            .member(member)
            .previousStatus(null) // 생성 시점에는 이전 상태 X
            .newStatus(MemberStatus.ACTIVE)
            .changedBy("SYSTEM:SIGNUP")
            .reason("신규 회원가입")
            .build();

        historyRepository.save(history);
    }

    @Transactional
    public void deleteMember(Long memberId, String changedBy, String reason) {
        Member member = memberRepository.findById(memberId)
            .orElseThrow(MemberNotFoundException::new);

        MemberStatus previousStatus = member.getStatus();
        member.delete();

        memberRepository.save(member);

        MemberStatusHistory history = MemberStatusHistory.builder()
            .member(member)
            .previousStatus(previousStatus)
            .newStatus(MemberStatus.DELETED)
            .changedBy(changedBy)
            .reason(reason)
            .build();

        historyRepository.save(history);
    }
}
