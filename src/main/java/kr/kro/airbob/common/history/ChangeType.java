package kr.kro.airbob.common.history;

// 이력 행이 어떤 변경에서 기록됐는지 분류
public enum ChangeType {
	CREATE,         // 최초 생성
	UPDATE,         // 일반 필드 변경
	STATUS_CHANGE,  // 상태 전이
	CANCEL,         // 취소
	DELETE          // 삭제(soft delete 포함)
}
