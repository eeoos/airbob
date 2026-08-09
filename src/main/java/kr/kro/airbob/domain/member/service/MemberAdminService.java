package kr.kro.airbob.domain.member.service;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import kr.kro.airbob.common.exception.AdminAccessDeniedException;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.dto.MemberAdminRequest.ChangeRole;
import kr.kro.airbob.domain.member.dto.MemberAdminResponse.RoleChanged;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberHistory;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.exception.MemberRoleChangeNotAllowedException;
import kr.kro.airbob.domain.member.repository.MemberHistoryRepository;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberAdminService {

	private final MemberRepository memberRepository;
	private final MemberHistoryRepository memberHistoryRepository;
	private final EntityManager entityManager;

	@Transactional
	public RoleChanged changeRole(Long actorId, Long targetId, ChangeRole request) {
		List<Long> ids = Stream.of(actorId, targetId)
			.distinct()
			.sorted()
			.toList();
		List<Member> lockedMembers = memberRepository.findAllByIdForUpdate(ids).stream()
			.sorted(Comparator.comparing(Member::getId))
			.toList();
		lockedMembers.forEach(member -> entityManager.refresh(member, LockModeType.PESSIMISTIC_WRITE));
		Map<Long, Member> locked = lockedMembers.stream()
			.collect(toMap(Member::getId, identity()));

		Member actor = requireActiveAdmin(locked.get(actorId));
		Member target = requireActiveTarget(locked.get(targetId));
		if (actorId.equals(targetId) && request.role() == MemberRole.MEMBER) {
			throw new MemberRoleChangeNotAllowedException();
		}
		if (target.getRole() == request.role()) {
			return new RoleChanged(targetId, target.getRole());
		}

		target.changeRole(request.role());
		memberHistoryRepository.findByMemberIdAndValidTo(targetId, HistoryConstants.FOREVER)
			.ifPresent(history -> history.close(LocalDateTime.now()));
		memberHistoryRepository.save(
			MemberHistory.open(target, ChangeType.ROLE_CHANGE, request.reason()));

		return new RoleChanged(targetId, target.getRole());
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
