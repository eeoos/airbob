package kr.kro.airbob.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.domain.auth.exception.AdminAccessDeniedException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberAdminServiceTest {

	@Mock
	private MemberRepository memberRepository;
	@Mock
	private MemberHistoryRepository memberHistoryRepository;
	@Mock
	private EntityManager entityManager;
	private MemberAdminService service;

	@BeforeEach
	void setUp() {
		service = new MemberAdminService(memberRepository, memberHistoryRepository, entityManager);
	}

	@Test
	void activeAdminPromotesActiveMember() {
		Member actor = member(1L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));
		given(memberHistoryRepository.findByMemberIdAndValidTo(10L, HistoryConstants.FOREVER))
			.willReturn(Optional.empty());

		RoleChanged result = service.changeRole(
			1L, 10L, new ChangeRole(MemberRole.ADMIN, "운영 관리자 지정"));

		assertThat(result).isEqualTo(new RoleChanged(10L, MemberRole.ADMIN));
		assertThat(target.getRole()).isEqualTo(MemberRole.ADMIN);
	}

	@Test
	void activeAdminRevokesAnotherAdminsRole() {
		Member actor = member(1L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		Member target = member(10L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));
		given(memberHistoryRepository.findByMemberIdAndValidTo(10L, HistoryConstants.FOREVER))
			.willReturn(Optional.empty());

		RoleChanged result = service.changeRole(
			1L, 10L, new ChangeRole(MemberRole.MEMBER, "관리자 권한 회수"));

		assertThat(result).isEqualTo(new RoleChanged(10L, MemberRole.MEMBER));
		assertThat(target.getRole()).isEqualTo(MemberRole.MEMBER);
	}

	@Test
	void identicalRoleReturnsSuccessWithoutHistoryChange() {
		Member actor = member(1L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));

		RoleChanged result = service.changeRole(
			1L, 10L, new ChangeRole(MemberRole.MEMBER, "동일 권한 재요청"));

		assertThat(result).isEqualTo(new RoleChanged(10L, MemberRole.MEMBER));
		assertThat(target.getRole()).isEqualTo(MemberRole.MEMBER);
		then(memberHistoryRepository).shouldHaveNoInteractions();
	}

	@Test
	void locksActorAndTargetInAscendingDistinctOrder() {
		Member target = member(1L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		Member actor = member(10L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(target, actor));
		given(memberHistoryRepository.findByMemberIdAndValidTo(1L, HistoryConstants.FOREVER))
			.willReturn(Optional.empty());

		service.changeRole(10L, 1L, new ChangeRole(MemberRole.ADMIN, "운영 관리자 지정"));

		then(memberRepository).should().findAllByIdForUpdate(List.of(1L, 10L));
	}

	@Test
	void refreshesEveryLockedRowInAscendingOrderBeforeActorValidation() {
		Member target = mock(Member.class);
		Member actor = mock(Member.class);
		given(target.getId()).willReturn(1L);
		given(actor.getId()).willReturn(10L);
		given(actor.getStatus()).willReturn(MemberStatus.ACTIVE);
		given(actor.getRole()).willReturn(MemberRole.MEMBER);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));

		assertThatThrownBy(() -> service.changeRole(
			10L, 1L, new ChangeRole(MemberRole.ADMIN, "운영 관리자 지정")))
			.isInstanceOf(AdminAccessDeniedException.class);

		InOrder order = inOrder(entityManager, actor);
		order.verify(entityManager).refresh(target, LockModeType.PESSIMISTIC_WRITE);
		order.verify(entityManager).refresh(actor, LockModeType.PESSIMISTIC_WRITE);
		order.verify(actor).getStatus();
	}

	@Test
	void missingActorIsDenied() {
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(target));

		assertThatThrownBy(() -> service.changeRole(
			1L, 10L, new ChangeRole(MemberRole.ADMIN, "운영 관리자 지정")))
			.isInstanceOf(AdminAccessDeniedException.class);
	}

	@Test
	void inactiveActorIsDenied() {
		Member actor = member(1L, MemberRole.ADMIN, MemberStatus.DORMANT);
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));

		assertThatThrownBy(() -> service.changeRole(
			1L, 10L, new ChangeRole(MemberRole.ADMIN, "운영 관리자 지정")))
			.isInstanceOf(AdminAccessDeniedException.class);
	}

	@Test
	void nonAdminActorIsDenied() {
		Member actor = member(1L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));

		assertThatThrownBy(() -> service.changeRole(
			1L, 10L, new ChangeRole(MemberRole.ADMIN, "운영 관리자 지정")))
			.isInstanceOf(AdminAccessDeniedException.class);
	}

	@Test
	void missingTargetIsRejected() {
		Member actor = member(1L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor));

		assertThatThrownBy(() -> service.changeRole(
			1L, 10L, new ChangeRole(MemberRole.ADMIN, "운영 관리자 지정")))
			.isInstanceOf(MemberNotFoundException.class);
	}

	@Test
	void inactiveTargetIsRejected() {
		Member actor = member(1L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.DELETED);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));

		assertThatThrownBy(() -> service.changeRole(
			1L, 10L, new ChangeRole(MemberRole.ADMIN, "운영 관리자 지정")))
			.isInstanceOf(MemberNotFoundException.class);
	}

	@Test
	void adminCannotRevokeOwnRoleAfterActorValidation() {
		Member actorAndTarget = member(1L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L)))
			.willReturn(List.of(actorAndTarget));

		assertThatThrownBy(() -> service.changeRole(
			1L, 1L, new ChangeRole(MemberRole.MEMBER, "자기 권한 회수")))
			.isInstanceOf(MemberRoleChangeNotAllowedException.class);

		then(memberRepository).should().findAllByIdForUpdate(List.of(1L));
		then(memberHistoryRepository).shouldHaveNoInteractions();
	}

	@Test
	void nonAdminSelfRevocationIsDeniedByActorValidation() {
		Member actorAndTarget = member(1L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L)))
			.willReturn(List.of(actorAndTarget));

		assertThatThrownBy(() -> service.changeRole(
			1L, 1L, new ChangeRole(MemberRole.MEMBER, "자기 권한 회수")))
			.isInstanceOf(AdminAccessDeniedException.class);

		then(memberHistoryRepository).shouldHaveNoInteractions();
	}

	@Test
	void realRoleChangeClosesCurrentHistoryAndSavesRoleChangeSnapshot() {
		Member actor = member(1L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		MemberHistory currentHistory = MemberHistory.builder()
			.memberId(10L)
			.role(MemberRole.MEMBER)
			.status(MemberStatus.ACTIVE)
			.changeType(ChangeType.CREATE)
			.changeReason("회원 가입")
			.validFrom(LocalDateTime.now().minusDays(1))
			.validTo(HistoryConstants.FOREVER)
			.build();
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));
		given(memberHistoryRepository.findByMemberIdAndValidTo(10L, HistoryConstants.FOREVER))
			.willReturn(Optional.of(currentHistory));
		ArgumentCaptor<MemberHistory> historyCaptor = ArgumentCaptor.forClass(MemberHistory.class);

		service.changeRole(
			1L, 10L, new ChangeRole(MemberRole.ADMIN, "  운영 관리자 지정  "));

		assertThat(currentHistory.getValidTo()).isNotEqualTo(HistoryConstants.FOREVER);
		then(memberHistoryRepository).should().save(historyCaptor.capture());
		MemberHistory savedHistory = historyCaptor.getValue();
		assertThat(savedHistory.getMemberId()).isEqualTo(10L);
		assertThat(savedHistory.getRole()).isEqualTo(MemberRole.ADMIN);
		assertThat(savedHistory.getChangeType()).isEqualTo(ChangeType.ROLE_CHANGE);
		assertThat(savedHistory.getChangeReason()).isEqualTo("운영 관리자 지정");
		assertThat(savedHistory.getValidTo()).isEqualTo(HistoryConstants.FOREVER);
	}

	private Member member(Long id, MemberRole role, MemberStatus status) {
		return Member.builder()
			.id(id)
			.role(role)
			.status(status)
			.build();
	}
}
