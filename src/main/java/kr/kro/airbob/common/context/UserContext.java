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

	public static void clear() {
		userThreadLocal.remove();
	}
}
