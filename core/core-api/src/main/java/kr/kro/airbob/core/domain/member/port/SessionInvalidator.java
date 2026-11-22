package kr.kro.airbob.core.domain.member.port;

public interface SessionInvalidator {
    void invalidateAll(Long memberId);
}
