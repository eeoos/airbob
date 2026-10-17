# Airbob API 사용 안내

Airbob V1 API의 요청·응답 구조는 코드에서 생성한 OpenAPI 명세로 확인한다. 호출 순서와 업무 규칙은 아래 사용 가이드를 함께 읽는다. 일반 회원·호스트 API와 ADMIN API를 구분하며, 일반 회원 API에도 로그인 필수 경로가 있다.

| 목적 | 문서 |
|---|---|
| 일반 회원과 호스트 API의 필드·타입·예시·오류 | [client.yaml](openapi/client.yaml) |
| 관리자 API의 필드·타입·예시·오류 | [admin.yaml](openapi/admin.yaml) |
| 인증·회원·숙소 작성·예약 달력·이미지·공통 규칙 | [사용 규칙](conventions.md) |
| 견적부터 예약·결제·취소까지 | [예약과 결제](reservation-payment.md) |
| 쿠폰 조회·발급·관리 | [쿠폰](coupons.md) |
| 리뷰 작성·목록·평점·이미지 | [리뷰](reviews.md) |
| 위시리스트 생성·저장 여부·메모·페이지 조회 | [위시리스트](wishlists.md) |
| 최근 본 숙소 기록·보관 기간·조회·삭제 | [최근 본 숙소](recently-viewed.md) |

## 문서 화면 열기

배포된 API 명세는 [일반 회원·호스트 Swagger UI](https://api.airbob.cloud/swagger-ui/index.html?urls.primaryName=client)와 [관리자 Swagger UI](https://api.airbob.cloud/swagger-ui/index.html?urls.primaryName=admin)에서 확인한다. 각 API를 펼치면 요청 매개변수, 응답 구조, 상태 코드와 오류 예시를 읽을 수 있다. 호출 순서와 업무 규칙은 위의 사용 가이드를 함께 읽는다.

로컬에서는 기존 개발 환경을 준비하고 `dev` 프로필로 앱을 실행한 뒤 [Swagger UI](http://localhost:8080/swagger-ui/index.html?urls.primaryName=client)를 연다. 기본 포트 8080 기준이며 `/swagger-ui.html`로도 접근할 수 있다.

문서 기능은 `dev`·`aws`·`oci` 프로필에서 활성화된다. 현재 앱의 기본 활성 프로필은 `dev`다. `test`가 함께 활성화되면 문서 기능은 기본적으로 꺼지며, 문서 전용 테스트만 명시적으로 켠다. 이 설정이 포함된 앱을 배포하면 해당 버전의 명세가 함께 제공된다. AWS의 과거 실험용 고정 이미지에는 새 설정이 소급 적용되지 않는다.

JSON 명세는 `/v3/api-docs/client`, `/v3/api-docs/admin`, YAML은 `/v3/api-docs.yaml/client`, `/v3/api-docs.yaml/admin`에서 조회할 수 있다. 문서 열람에는 로그인이 필요하지 않다. `Try it out`은 문서를 제공하는 서버의 실제 API를 호출한다. 인증이 필요한 API를 실행할 때는 `client` 그룹의 `인증 → 로그인`을 먼저 실행한다. 앱과 같은 호스트·포트의 UI를 사용하면 로그인 응답의 HttpOnly `SESSION_ID` 쿠키를 브라우저가 저장하고 이후 요청에 보낸다. 쿠키 값을 Authorize 입력창에 붙여 넣는 방식은 브라우저의 Cookie 헤더 제한 때문에 사용하지 않는다. ADMIN API 호출에는 실제 ADMIN 회원 로그인이 필요하다.

로그인 성공 후에는 `GET /api/v1/auth/me`를 실행해 `200` 응답으로 세션을 확인한다. Swagger의 `Response headers`에 `Set-Cookie`가 보이지 않아도 쿠키가 누락된 것은 아니다. 브라우저는 이 헤더를 JavaScript에 노출하지 않고, HttpOnly 쿠키 값도 JavaScript로 읽을 수 없게 한다. 브라우저의 쿠키 저장·전송은 정상적으로 이루어지며, Swagger의 자물쇠 표시는 실제 로그인 상태와 별개다. 쿠키 속성을 직접 확인하려면 브라우저 개발자 도구의 Network 또는 Cookies 화면을 사용한다.

Notion에서는 이 문서와 명세 화면의 링크를 공유할 수 있다. API 필드 목록의 원본은 저장소에서 관리한다.

## 명세 생성과 갱신

JDK 21에서 실행한다. 아래 작업은 실제 Controller, DTO, Jackson 설정, 인증 필터와 매개변수 해석기를 사용하며, 서비스와 저장소 의존성을 테스트 대역으로 대체한다. 문서 생성에는 MySQL·Redis·Kafka·Elasticsearch·외부 결제 연결이 필요하지 않다.

```bash
# 생성과 계약 검증. 결과는 build/openapi/에만 기록한다.
./gradlew generateOpenApi

# 검증을 통과한 결과를 docs/api/openapi/에 반영한다.
./gradlew updateOpenApi

# 코드에서 생성한 결과와 Git에 저장된 명세가 같은지 확인한다.
./gradlew verifyOpenApi
```

`./gradlew build`도 명세 검증을 실행한다. 저장된 YAML을 직접 수정하면 검증에 실패한다. 갱신은 명시적인 `updateOpenApi` 작업으로만 수행한다. 일반 빌드와 CI 검증은 소스 문서를 덮어쓰지 않는다.

## API를 수정할 때

1. Controller·DTO와 실제 동작을 먼저 변경하고 관련 테스트를 수정한다.
2. Controller의 `@Operation`, 성공 응답, `@ApiErrors`와 DTO의 `@Schema`를 갱신한다. 같은 이름의 중첩 DTO에는 고유한 스키마 이름을 유지한다.
3. 호출 순서나 오류 처리 방법이 달라졌다면 해당 사용 가이드를 수정한다.
4. `./gradlew updateOpenApi verifyOpenApi`를 실행하고 생성된 변경을 검토한다.
5. 코드·테스트·YAML·가이드를 같은 PR에 포함한다.

`@ApiErrors`는 실제 오류 열거형에서 메시지와 예시를 생성한다. 주요 오류를 명시하되 모든 오류를 모든 API에 일괄적으로 붙이지 않는다. 성공·실패 예시에는 가상의 식별자와 `example.invalid` 이메일을 사용한다.

## 검증 범위

문서 테스트는 실제 요청 매핑과 명세의 일치, 그룹 구분, 인증 필터와 명세의 일치, JSON 스키마 참조와 OpenAPI 구조를 검사한다. 인증 응답과 세션 쿠키 흐름은 실제 MVC와 Redis 통합 테스트로 검증한다. 회원가입과 권한 변경 응답을 생성된 스키마로 검증하며, 실제 MySQL에서 가입·중복·동시 요청을 확인한다. 숙소 응답을 생성된 스키마로 검증한다. 숙소 업로드는 임시 HTTP 서버에서 multipart 바인딩·파일 및 요청 크기 제한을 검사하며 실제 S3 업로드는 수행하지 않는다. 예약 요청·응답과 오류는 실제 MVC 및 생성된 스키마로 검증하며, 견적·체크아웃·보유 해제·결제 시도·조회는 MySQL 통합 테스트로 확인한다. 결제 요청·상태·관리자 응답은 실제 MVC와 생성된 스키마로 검증하며, 승인·취소·조회·대사·미결제 확정은 MySQL 통합 테스트로 확인한다. 쿠폰은 실제 MySQL에서 생성 ID와 재고 준비 연결, 길이·시간 입력 검증 및 롤백을 확인하며, 조회 상태·수정 조건·오류 응답은 MVC 계약 테스트와 대조한다. 리뷰는 작성 자격·세 정렬의 커서 조회·동시 작성/수정/삭제의 집계를 MySQL로 검증하며, 요청·응답 계약과 실제 HTTP 업로드 제한도 확인한다. 위시리스트는 목록·저장 상태·상세 조회와 동시 변경의 개수·대표 숙소 정합성을 MySQL로 확인하며, 필수 입력·연결 ID·오류 응답을 MVC 계약 테스트로 검증한다. 최근 본 숙소는 실제 Redis에서 재기록·동시 추가의 100개 제한·만료·삭제를 검증하고, MySQL과 함께 공개 상태·현재 정보·찜 여부·조회 순서를 확인한다.

CI에서 앱 변경은 기존 앱 빌드의 `check`에 포함하여 검사한다. API 문서만 변경하면 별도의 문서 검증을 수행하며 OCI 배포를 요구하지 않는다. 필수 `CI result`는 선택된 문서 검증이 실패·취소·예상치 않게 생략된 경우 실패한다.
