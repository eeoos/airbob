package kr.kro.airbob.core.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.core.domain.member.repository.MemberHistoryRepository;
import kr.kro.airbob.core.domain.member.repository.MemberRepository;
import kr.kro.airbob.core.support.error.AdminAccessDeniedException;
import kr.kro.airbob.core.support.persistence.history.ChangeType;
import kr.kro.airbob.core.support.persistence.history.HistoryConstants;

@ExtendWith(MockitoExtension.class)
class MemberAdminServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-12T05:30:00Z");
	private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Mock
	private MemberRepository memberRepository;
	@Mock
	private MemberHistoryRepository memberHistoryRepository;
	private MemberAdminService service;

	@BeforeEach
	void setUp() {
		service = new MemberAdminService(new MemberReader(memberRepository),
			new MemberWriter(memberRepository, memberHistoryRepository, FIXED_CLOCK));
	}

	@Test
	void activeAdminPromotesActiveMember() {
		Member actor = member(1L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));
		given(memberHistoryRepository.findByMemberIdAndValidTo(10L, HistoryConstants.FOREVER))
			.willReturn(Optional.empty());

		MemberRole result = service.changeRole(
			1L, 10L, MemberRole.ADMIN, "운영 관리자 지정");

		assertThat(result).isEqualTo(MemberRole.ADMIN);
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

		MemberRole result = service.changeRole(
			1L, 10L, MemberRole.MEMBER, "관리자 권한 회수");

		assertThat(result).isEqualTo(MemberRole.MEMBER);
		assertThat(target.getRole()).isEqualTo(MemberRole.MEMBER);
	}

	@Test
	void identicalRoleReturnsSuccessWithoutHistoryChange() {
		Member actor = member(1L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));

		MemberRole result = service.changeRole(
			1L, 10L, MemberRole.MEMBER, "동일 권한 재요청");

		assertThat(result).isEqualTo(MemberRole.MEMBER);
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

		service.changeRole(10L, 1L, MemberRole.ADMIN, "운영 관리자 지정");

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
			10L, 1L, MemberRole.ADMIN, "운영 관리자 지정"))
			.isInstanceOf(AdminAccessDeniedException.class);

		InOrder order = inOrder(memberRepository, actor);
		then(memberRepository).should(order).refreshWithWriteLock(target);
		then(memberRepository).should(order).refreshWithWriteLock(actor);
		then(actor).should(order).getStatus();
	}

	@Test
	void missingActorIsDenied() {
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(target));

		assertThatThrownBy(() -> service.changeRole(
			1L, 10L, MemberRole.ADMIN, "운영 관리자 지정"))
			.isInstanceOf(AdminAccessDeniedException.class);
	}

	@Test
	void inactiveActorIsDenied() {
		Member actor = member(1L, MemberRole.ADMIN, MemberStatus.DORMANT);
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));

		assertThatThrownBy(() -> service.changeRole(
			1L, 10L, MemberRole.ADMIN, "운영 관리자 지정"))
			.isInstanceOf(AdminAccessDeniedException.class);
	}

	@Test
	void nonAdminActorIsDenied() {
		Member actor = member(1L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));

		assertThatThrownBy(() -> service.changeRole(
			1L, 10L, MemberRole.ADMIN, "운영 관리자 지정"))
			.isInstanceOf(AdminAccessDeniedException.class);
	}

	@Test
	void missingTargetIsRejected() {
		Member actor = member(1L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor));

		assertThatThrownBy(() -> service.changeRole(
			1L, 10L, MemberRole.ADMIN, "운영 관리자 지정"))
			.isInstanceOf(MemberNotFoundException.class);
	}

	@Test
	void inactiveTargetIsRejected() {
		Member actor = member(1L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		Member target = member(10L, MemberRole.MEMBER, MemberStatus.DELETED);
		given(memberRepository.findAllByIdForUpdate(List.of(1L, 10L)))
			.willReturn(List.of(actor, target));

		assertThatThrownBy(() -> service.changeRole(
			1L, 10L, MemberRole.ADMIN, "운영 관리자 지정"))
			.isInstanceOf(MemberNotFoundException.class);
	}

	@Test
	void adminCannotRevokeOwnRoleAfterActorValidation() {
		Member actorAndTarget = member(1L, MemberRole.ADMIN, MemberStatus.ACTIVE);
		given(memberRepository.findAllByIdForUpdate(List.of(1L)))
			.willReturn(List.of(actorAndTarget));

		assertThatThrownBy(() -> service.changeRole(
			1L, 1L, MemberRole.MEMBER, "자기 권한 회수"))
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
			1L, 1L, MemberRole.MEMBER, "자기 권한 회수"))
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
			1L, 10L, MemberRole.ADMIN, "운영 관리자 지정");

		assertThat(currentHistory.getValidTo()).isNotEqualTo(HistoryConstants.FOREVER);
		then(memberHistoryRepository).should().save(historyCaptor.capture());
		MemberHistory savedHistory = historyCaptor.getValue();
		assertThat(savedHistory.getMemberId()).isEqualTo(10L);
		assertThat(savedHistory.getRole()).isEqualTo(MemberRole.ADMIN);
		assertThat(savedHistory.getChangeType()).isEqualTo(ChangeType.ROLE_CHANGE);
		assertThat(savedHistory.getChangeReason()).isEqualTo("운영 관리자 지정");
		LocalDateTime expected = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
		assertThat(currentHistory.getValidTo()).isEqualTo(expected);
		assertThat(savedHistory.getValidFrom()).isEqualTo(expected);
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
