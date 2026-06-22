package kr.kro.airbob.domain.member.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.history.HistoryConstants;
import kr.kro.airbob.common.history.MasterHistoryBase;
import kr.kro.airbob.domain.member.common.MemberRole;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// 회원 이력 — 전체 행 스냅샷(Master 성격, valid_from/valid_to로 시점 조회). 비밀번호는 스냅샷 제외.
// 변경 시 직전 현재 행을 close()로 닫고 새 스냅샷을 INSERT(SCD Type-2).
@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberHistory extends MasterHistoryBase {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long memberId;

	// --- 원본 비즈니스 컬럼 스냅샷 (password 제외) ---
	private String email;
	private String nickname;
	@Enumerated(EnumType.STRING)
	private MemberRole role;
	@Enumerated(EnumType.STRING)
	private MemberStatus status;
	private String thumbnailImageUrl;

	// --- 원본 최초 생성 정보(스냅샷) ---
	private LocalDateTime createdAt;
	private Long createdBy;

	// 사용자 요청에서 비롯된 변경
	public static MemberHistory open(Member member, ChangeType changeType, String changeReason) {
		return build(member, changeType, changeReason,
			UserContext.currentSourceSystem(), UserContext.currentClientIp());
	}

	// 시스템/배치에서 비롯된 변경 (예: 가입은 비인증 컨텍스트)
	public static MemberHistory openSystem(Member member, ChangeType changeType, String changeReason,
		String sourceSystem) {
		return build(member, changeType, changeReason, sourceSystem, null);
	}

	private static MemberHistory build(Member m, ChangeType changeType, String changeReason,
		String sourceSystem, String clientIp) {
		return MemberHistory.builder()
			.memberId(m.getId())
			.email(m.getEmail())
			.nickname(m.getNickname())
			.role(m.getRole())
			.status(m.getStatus())
			.thumbnailImageUrl(m.getThumbnailImageUrl())
			.createdAt(m.getCreatedAt())
			.createdBy(m.getCreatedBy())
			.changeType(changeType)
			.changeReason(changeReason)
			.sourceSystem(sourceSystem)
			.clientIp(clientIp)
			.validFrom(LocalDateTime.now())
			.validTo(HistoryConstants.FOREVER)
			.build();
	}
}
