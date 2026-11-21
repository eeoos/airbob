package kr.kro.airbob.common.context;

// id: 인증된 회원 식별자(비인증/시스템이면 null)
// clientIp, sourceSystem: 이력 기록용 요청 컨텍스트 (인증 요청에서 필터가 주입)
public record UserInfo(Long id, String clientIp, String sourceSystem) {

	public UserInfo(Long id) {
		this(id, null, null);
	}
}
