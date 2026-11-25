package kr.kro.airbob.core.api.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/** 로그인 세션 ID를 담는 HttpOnly 쿠키. 이름과 속성을 이 클래스에서만 정한다. */
public final class SessionCookie {

	public static final String NAME = "SESSION_ID";

	// 서버의 세션 TTL(1시간)과 같다.
	private static final int MAX_AGE_SECONDS = 3600;

	private SessionCookie() {}

	public static Cookie create(String sessionId) {
		Cookie cookie = new Cookie(NAME, sessionId);
		cookie.setHttpOnly(true);
		cookie.setPath("/");
		cookie.setMaxAge(MAX_AGE_SECONDS);
		return cookie;
	}

	/** 요청의 세션 ID. 쿠키가 없으면 null이다. */
	public static String read(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (NAME.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}
}
