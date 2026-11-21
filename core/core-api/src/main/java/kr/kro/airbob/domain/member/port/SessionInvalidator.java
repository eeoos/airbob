package kr.kro.airbob.domain.member.port;

public interface SessionInvalidator {
    void invalidateAll(Long memberId);
}
