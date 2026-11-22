package kr.kro.airbob.core.domain.member;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.support.error.AdminAccessDeniedException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberAdminService {

	private final MemberReader memberReader;
	private final MemberWriter memberWriter;

	/**
	 * 관리자가 대상 회원의 역할을 바꾸고 바뀐 뒤의 역할을 돌려준다.
	 * 실행자와 대상을 ID 오름차순으로 잠근 뒤 현재 행으로 권한과 상태를 확인한다. 같은 역할이면 이력 없이 그대로 둔다.
	 */
	@Transactional
	public MemberRole changeRole(Long actorId, Long targetId, MemberRole role, String reason) {
		Map<Long, Member> locked = memberReader.readAllForUpdate(List.of(actorId, targetId));

		requireActiveAdmin(locked.get(actorId));
		Member target = requireActiveTarget(locked.get(targetId));
		if (actorId.equals(targetId) && role == MemberRole.MEMBER) {
			throw new MemberRoleChangeNotAllowedException();
		}
		if (target.getRole() == role) {
			return target.getRole();
		}

		memberWriter.changeRole(target, role, reason);
		return target.getRole();
	}

	private Member requireActiveAdmin(Member member) {
		if (member == null
			|| member.getStatus() != MemberStatus.ACTIVE
			|| member.getRole() != MemberRole.ADMIN) {
			throw new AdminAccessDeniedException();
		}
		return member;
	}

	private Member requireActiveTarget(Member member) {
		if (member == null || member.getStatus() != MemberStatus.ACTIVE) {
			throw new MemberNotFoundException();
		}
		return member;
	}
}
