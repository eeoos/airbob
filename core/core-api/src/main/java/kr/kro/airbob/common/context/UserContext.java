package kr.kro.airbob.common.context;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UserContext {

	private static final ThreadLocal<UserInfo> userThreadLocal = new ThreadLocal<>();

	public static void set(UserInfo userInfo) {
		userThreadLocal.set(userInfo);
	}

	public static UserInfo get() {
		return userThreadLocal.get();
	}

	// 이력 기록 편의: 현재 요청의 IP / 출처 시스템 (없으면 null)
	public static String currentClientIp() {
		UserInfo info = userThreadLocal.get();
		return info == null ? null : info.clientIp();
	}

	public static String currentSourceSystem() {
		UserInfo info = userThreadLocal.get();
		return info == null ? null : info.sourceSystem();
	}

	public static void clear() {
		userThreadLocal.remove();
	}
}
