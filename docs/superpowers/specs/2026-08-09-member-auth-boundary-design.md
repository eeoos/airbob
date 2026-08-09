# 회원·인증 경계 정리 설계

## 목적

`member`와 `auth`를 하나의 패키지로 합치지 않고 각자의 변화 이유를 유지한다. 대신 현재의 양방향 의존과 책임 혼합을 정리해 회원 기능이 Redis 세션 구현에 직접 결합되지 않게 한다.

현재 Redis 세션 인증의 동작과 API 계약은 유지한다. 향후 JWT, 리프레시 토큰 또는 소셜 로그인을 추가할 때 회원 가입·탈퇴·권한·이력 코드를 함께 수정하지 않아도 되는 경계를 만드는 것이 목표다.

## 현재 문제

- `auth`는 로그인 과정에서 `MemberRepository`, `Member`, `MemberStatus`를 사용한다. 인증이 회원 식별 정보를 필요로 하므로 `auth → member` 의존은 허용한다.
- `MemberService`는 탈퇴 시 `SessionRedisRepository`를 직접 호출한다. 이 의존 때문에 회원 기능이 Redis 키와 세션 저장 방식에 결합된다.
- `MemberAdminService`는 `auth` 패키지의 `AdminAccessDeniedException`을 사용한다. 회원 서비스가 인증 모듈의 구체 예외를 역참조한다.
- `AuthService#getMemberInfo()`는 로그인·로그아웃이 아닌 회원 정보 조회 책임을 수행한다.
- 여러 도메인의 컨트롤러가 `@CurrentMemberId`를 사용한다. 이는 인증 모듈이 외부에 제공하는 웹 어댑터 계약이다.

## 접근 방식 비교

### `member`와 `auth` 완전 통합

회원 엔티티, 회원 이력, Redis 세션, 필터, 인터셉터와 로그인 서비스를 `member` 아래로 모두 이동한다. 파일 위치는 하나로 모이지만 서로 다른 변화 이유를 가진 코드가 섞이고, JWT나 소셜 로그인 도입 시 회원 도메인 패키지까지 변경 범위가 넓어진다. 대량의 패키지·테스트 이동에 비해 동작상 이점이 없으므로 채택하지 않는다.

### `identity` 상위 패키지 도입

`domain.identity.member`, `domain.identity.auth`, `domain.identity.security`로 묶어 같은 식별자 관리 영역임을 표현한다. 개념적으로는 타당하지만 현재 문제인 구체 구현 의존을 해결하지 않고 패키지 이동량만 크게 만든다. 모듈 수가 늘거나 별도 배포 경계를 검토할 때 다시 판단한다.

### 분리 유지와 의존 방향 정리 — 채택

`member`는 회원 상태와 수명주기를, `auth`는 인증과 세션을 담당한다. `member`가 필요로 하는 세션 무효화 기능은 작은 포트로 표현하고 Redis 구현은 `auth`에 남긴다. 외부 API와 세션 정책을 바꾸지 않으면서 현재 결합만 제거할 수 있다.

## 책임 경계

### `domain.member`

- 회원 엔티티와 저장소
- 가입·탈퇴 및 회원 정보 조회
- 회원 상태·역할 변경
- 회원 변경 이력
- 탈퇴 시 필요한 세션 무효화 포트

### `domain.auth`

- 로그인·로그아웃
- 비밀번호 검증과 세션 발급
- Redis 세션 저장·조회·폐기
- 요청 인증 필터와 관리자 인가 인터셉터
- `@CurrentMemberId`와 MVC 인자 해석

`auth`가 회원 정보를 조회하는 `auth → member` 의존은 유지한다. `member`의 엔티티와 서비스는 `auth`의 구체 구현을 참조하지 않는다. 단, `member.api`를 포함한 웹 컨트롤러는 인증 모듈의 공개 웹 계약인 `@CurrentMemberId`를 사용할 수 있다. 이 예외는 웹 어댑터에만 허용한다.

## 구성 요소 변경

### 회원 정보 조회 이동

`AuthService#getMemberInfo(Long memberId)`를 `MemberService`로 이동한다. `AuthService`는 로그인과 로그아웃만 담당한다.

기존 `GET /api/v1/auth/me` 경로와 응답 DTO `MemberResponse.MeInfo`는 유지한다. `AuthController`가 해당 요청에서 `MemberService`를 호출하게 해 외부 계약 변경을 피한다.

### `SessionInvalidator` 포트

`domain.member.port.SessionInvalidator`를 추가한다.

```java
public interface SessionInvalidator {
    void invalidateAll(Long memberId);
}
```

`MemberService`는 회원 상태와 이력을 변경한 뒤 같은 서비스 호출 안에서 이 포트만 호출한다. `SessionRedisRepository`의 기존 `deleteAllSessions()`를 `invalidateAll()`로 이름을 바꾸고 포트 구현 메서드로 사용한다. 기존 호출부와 테스트도 새 포트 계약으로 변경한다.

세션 무효화는 보안상 즉시 적용되어야 하므로 비동기 도메인 이벤트로 바꾸지 않는다. 향후 JWT를 사용하면 Redis 구현 대신 세션 세대 증가, 블랙리스트 또는 토큰 폐기 구현으로 교체할 수 있다.

### 관리자 접근 예외 이동

`AdminAccessDeniedException`을 `domain.auth.exception`에서 `common.exception`으로 이동한다. 오류 코드 `M006`, HTTP 403과 응답 형식은 그대로 유지한다.

`AdminAuthInterceptor`와 `MemberAdminService`가 동일한 공통 예외를 사용한다. 잠금 이후 호출 관리자의 `ACTIVE + ADMIN` 상태를 다시 검사하는 서비스 방어 로직도 유지한다.

### 인증 웹 계약 유지

`CurrentMemberId`, `CurrentMemberIdArgumentResolver`, `SessionAuthFilter`, `AdminAuthInterceptor`는 이번 작업에서 이동하지 않는다. 여러 컨트롤러가 사용하는 `@CurrentMemberId`는 `auth`가 제공하는 공개 웹 계약으로 취급한다.

별도의 `common.security` 패키지나 인증 제공자 계층은 실제 필요가 생기기 전까지 추가하지 않는다.

## 처리 흐름

### 로그인

1. `AuthController`가 로그인 요청을 받는다.
2. `AuthService`가 활성 회원과 비밀번호를 검증한다.
3. `SessionIssuanceService`가 회원 행 잠금을 확인한 뒤 세션을 발급한다.
4. `SessionRedisRepository`가 기존 Redis 키 정책으로 세션을 저장한다.

이 흐름은 변경하지 않는다.

### 내 정보 조회

1. `SessionAuthFilter`와 `@CurrentMemberId`가 현재 회원 ID를 제공한다.
2. `AuthController`가 `MemberService#getMemberInfo()`를 호출한다.
3. `MemberService`가 활성 회원을 조회하고 기존 `MemberResponse.MeInfo`를 반환한다.

### 회원 탈퇴

1. `MemberService`가 회원 행을 잠그고 회원 상태와 이력을 변경한다.
2. `MemberService`가 `SessionInvalidator.invalidateAll(memberId)`을 호출한다.
3. 주입된 `SessionRedisRepository` 구현이 회원의 활성 키와 세션을 폐기한다.

DB 트랜잭션과 Redis 명령은 하나의 분산 트랜잭션이 아니며 이번 작업에서 원자화하지 않는다. 기존처럼 회원 행 잠금과 세션 활성 키 우선 제거 정책을 유지한다.

## 오류 처리

- 내 정보 조회 대상이 없거나 비활성이면 기존 `M001`, HTTP 404를 유지한다.
- 관리자 권한이 없으면 기존 `M006`, HTTP 403을 유지한다.
- Redis 세션 무효화 실패는 기존처럼 호출자에게 전파해 회원 탈퇴 트랜잭션을 실패시킨다.
- 로그인·로그아웃의 기존 인증 예외와 응답 계약은 변경하지 않는다.

## 테스트 전략

### `AuthServiceTest`

- 기존 로그인·로그아웃 테스트를 유지한다.
- 회원 정보 조회 테스트는 `MemberServiceTest`로 이동해 `AuthService`의 인증 책임만 검증한다.

### `MemberServiceTest`

- 활성 회원 정보 조회와 비활성·미존재 회원 거부를 검증한다.
- 회원 탈퇴 시 `SessionInvalidator.invalidateAll(memberId)`이 호출되는지 검증한다.
- 테스트가 `SessionRedisRepository` 구체 타입에 의존하지 않게 한다.

### 인증·통합 테스트

- 기존 `GET /api/v1/auth/me` 요청과 응답 계약이 유지되는지 검증한다.
- 회원 탈퇴 후 기존 세션이 거부되는 통합 테스트를 유지한다.
- 관리자 권한 거부가 이동된 공통 예외로 동일한 `M006`, HTTP 403을 반환하는지 검증한다.

관련 단위·통합 테스트와 `git diff --check`를 실행한다.

## 범위 제외

- `member`와 `auth`의 패키지 대이동 또는 `identity` 상위 패키지 도입
- JWT, 리프레시 토큰, OAuth 또는 소셜 로그인 구현
- 인증 제공자·토큰 전략과 같은 선행 추상화
- `@CurrentMemberId`, 필터, 인터셉터의 공통 패키지 이동
- Redis 세션 명령의 Lua 원자화 또는 키 정책 변경
- `/api/v1/auth/me` URL과 응답 형식 변경
- 비동기 회원 탈퇴 이벤트 도입

## 완료 조건

- `AuthService`는 로그인·로그아웃만 담당한다.
- 회원 정보 조회는 `MemberService`가 담당하며 기존 API 계약이 유지된다.
- `MemberService`는 `SessionRedisRepository`를 직접 참조하지 않는다.
- `SessionRedisRepository`는 `SessionInvalidator` 포트를 구현한다.
- `MemberAdminService`는 `domain.auth.exception`을 참조하지 않는다.
- 기존 오류 코드와 Redis 세션 동작이 유지된다.
- 관련 테스트와 `git diff --check`가 통과한다.
