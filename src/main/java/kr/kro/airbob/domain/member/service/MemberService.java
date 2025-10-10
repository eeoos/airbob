package kr.kro.airbob.domain.member.service;

import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.dto.MemberRequestDto.SignupMemberRequestDto;
import kr.kro.airbob.domain.member.exception.DuplicatedEmailException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;

    @Transactional
    public void createMember(SignupMemberRequestDto request) {
        if(memberRepository.existsByEmail(request.getEmail())){
            throw new DuplicatedEmailException();
        }

        String hashedPassword = BCrypt.hashpw(request.getPassword(), BCrypt.gensalt());

        Member member = Member.createMember(request, hashedPassword);
        memberRepository.save(member);
    }
}
