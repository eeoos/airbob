# 공통 코드 API 사용 안내

공통 코드는 화면의 선택 목록에 사용하는 식별자와 표시명을 관리한다. 대표 그룹은 `AMENITY_TYPE`(편의시설), `ACCOMMODATION_TYPE`(숙소 유형)이며 관리자가 새 그룹을 등록할 수도 있다. 업무 API에 값을 전달할 때는 표시명 `name`이 아닌 `code`를 사용한다.

공개 조회는 [client.yaml](openapi/client.yaml)의 `공통 코드`, 관리자 기능은 [admin.yaml](openapi/admin.yaml)의 `공통 코드 관리` 태그에서 확인한다. JSON 필드는 snake_case다.

## 경로와 권한

| 작업 | 메서드와 경로 | 성공 응답 | 권한 |
|---|---|---|---|
| 활성 코드 조회 | `GET /api/v1/common-codes/{group}` | 200, `data` 배열 | 로그인 불필요 |
| 그룹 전체 조회 | `GET /api/v1/admin/common-code-groups` | 200, `data` 배열 | 활성 ADMIN |
| 그룹 생성 | `POST /api/v1/admin/common-code-groups` | 201, 생성한 그룹 | 활성 ADMIN |
| 그룹 수정 | `PATCH /api/v1/admin/common-code-groups/{groupCode}` | 200, 수정한 그룹 | 활성 ADMIN |
| 그룹의 코드 전체 조회 | `GET /api/v1/admin/common-codes/{group}` | 200, `data` 배열 | 활성 ADMIN |
| 코드 생성 | `POST /api/v1/admin/common-codes/{group}` | 201, 생성한 코드 | 활성 ADMIN |
| 코드 수정 | `PATCH /api/v1/admin/common-codes/{group}/{code}` | 200, 수정한 코드 | 활성 ADMIN |

관리자 요청은 로그인 응답의 HttpOnly `SESSION_ID` 쿠키를 브라우저가 자동 전송한다. 세션이 없거나 만료되면 401/M004이며, 세션이 있어도 현재 활성 ADMIN이 아니면 403/M006이다. 회원 ID나 세션 값을 요청 폼에 입력하지 않는다. 공개 조회는 세션 없이도, 만료된 쿠키가 있어도 가능하다.

경로의 그룹과 코드 식별자는 대문자로 정규화하므로 `amenity_type`, `wifi`도 조회·수정에 사용할 수 있다. 그룹·코드 목록에는 페이지·커서·크기 입력이 없고 전체 목록을 반환한다. 생성 응답에는 `Location` 헤더가 없으므로 `data.group_code` 또는 `data.code`를 후속 요청에 사용한다.

## 공개 조회와 관리자 조회

공개 조회는 **그룹과 코드가 모두 활성**인 항목만 반환한다. 비활성 그룹이나 활성 코드가 없는 그룹은 다음과 같이 성공한다.

```json
{"success": true, "data": []}
```

그룹 자체가 없으면 빈 배열이 아니라 **404/CC001**이다. 공개 결과에는 `code`, `name`, `sort_order`만 있고 설명·활성 상태는 없다.

```json
{
  "success": true,
  "data": [
    {"code": "WIFI", "name": "무선 인터넷", "sort_order": 1}
  ]
}
```

관리자 그룹 조회는 비활성 그룹도 포함하며 `group_code` 오름차순이다. 관리자 코드 조회는 그룹의 활성 여부와 관계없이 해당 그룹의 활성·비활성 코드를 모두 반환한다. 코드가 없는 기존 그룹은 빈 배열, 없는 그룹은 404/CC001이다.

공개·관리자 코드 목록은 `sort_order` 오름차순이며, 같은 값이면 `code` 오름차순으로 정렬한다. `sort_order`는 음수와 중복 값을 허용한다.

## 생성 입력과 기본값

POST와 PATCH에는 `Content-Type: application/json`을 사용한다.

| 대상 | 필수 입력 | 선택 입력과 기본값 |
|---|---|---|
| 그룹 생성 | `group_code`, `group_name` | `description`: 생략·null이면 설명 없음, `is_active`: 생략·null이면 true |
| 코드 생성 | `code`, `name` | `description`: 생략·null이면 설명 없음, `sort_order`: 생략·null이면 0, `is_active`: 생략·null이면 true |

그룹·코드 식별자는 생성 시 앞뒤 공백을 제거하고 대문자로 저장한다. 공백 제거 후 1~50자이며 영문자로 시작하고 영문·숫자·밑줄만 사용할 수 있다. 그룹 코드가 전체에서 고유하고, 개별 코드는 **같은 그룹 안에서만 고유**하다. 이름은 최대 100자이며 빈 값·공백만 있는 값은 허용하지 않는다. 설명은 최대 255자이며 빈 문자열도 허용한다. `sort_order`는 부호 있는 32비트 정수이고 음수도 유효하다.

새 코드는 기존 그룹에만 생성할 수 있으며, 그룹이 비활성이어도 관리자 생성·수정은 가능하다. 같은 식별자를 동시에 생성해도 하나만 생성되며 나머지는 409로 거절한다. 중복 요청이 기존 값을 덮어쓰지 않는다.

예를 들어 코드 생성 요청은 다음과 같다.

```http
POST /api/v1/admin/common-codes/AMENITY_TYPE
Content-Type: application/json

{
  "code": "EXAMPLE_AMENITY",
  "name": "예시 편의시설",
  "description": "시설 설명",
  "sort_order": 100,
  "is_active": true
}
```

**요청은 `is_active`, 응답은 `active`다.** 관리자 코드 응답에는 `code`, `name`, `description`, `sort_order`, `active`가 있다. 그룹 응답에는 `group_code`, `group_name`, `description`, `active`가 있다. 설명은 `null` 또는 빈 문자열일 수 있다.

## 부분 수정

그룹 PATCH는 `group_name`, `description`, `is_active`를, 코드 PATCH는 `name`, `description`, `sort_order`, `is_active`를 수정한다.

- 생략하거나 `null`인 필드는 기존 값을 유지한다. 빈 객체 `{}`도 허용한다.
- 설명을 비우려면 `"description": ""`을 보낸다. `null`은 삭제 요청이 아니다.
- 이름에 빈 문자열이나 공백만 있는 값은 보낼 수 없다.
- `sort_order: 0`, `is_active: false`도 실제 변경값으로 처리한다.
- 그룹·코드 식별자를 변경하거나 물리적으로 삭제하는 API는 없다. 노출 중단은 활성 상태를 false로 변경한다.

그룹을 비활성화하면 공개 목록에서 그룹 전체를 숨기지만 하위 코드들의 `active` 값 자체는 바꾸지 않는다. 그룹을 다시 활성화하면 그중 활성인 코드들이 다시 표시된다. 이미 숙소에 저장된 유형·편의시설 코드가 함께 삭제되는 것은 아니다.

코드 수정 시 그룹 또는 코드가 없어 `(group, code)` 조합을 찾지 못하면 **404/CC002**다. 그룹 없음만 따로 검사하는 관리자 코드 조회·생성의 404/CC001과 구분한다.

## 변경 반영과 캐시

관리자 쓰기와 관리자 조회는 DB 값을 사용한다. 공개 조회는 **서버 인스턴스별 1분 캐시**를 사용하며 관리자 변경이 캐시를 즉시 비우지는 않는다.

캐시는 적재 시점부터 1분간 유지되고, 만료 후 다음 조회에서 DB를 다시 읽는다. 활성 목록뿐 아니라 비활성 그룹의 빈 목록과 존재하지 않는 그룹의 404 상태도 캐시한다. 따라서 생성 직후 공개 조회가 잠시 404이거나, 비활성화 직후 이전 코드가 잠시 노출될 수 있다. 서버가 여러 대이면 각 인스턴스의 캐시 적재 시점에 따라 반영 시점이 다를 수 있다.

숙소 유형·편의시설의 쓰기 검증도 이 공개 조회 서비스를 사용하므로 활성 상태 변경 직후에는 같은 캐시 지연이 적용될 수 있다.

## 주요 오류

| 상태·코드 | 상황 |
|---|---|
| 400/C001 | 필수 생성 값 누락, 이름이 공백뿐임, 식별자 형식·필드 길이 초과, 해석할 수 없는 JSON·필드 타입, 요청 본문 누락 |
| 401/M004 | 관리자 API의 세션 없음·만료 |
| 403/M006 | 관리자 API 호출 회원이 현재 활성 ADMIN이 아님 |
| 404/CC001 | 공개 코드 조회·관리자 코드 조회·코드 생성의 그룹 없음, 또는 그룹 수정 대상 없음 |
| 404/CC002 | 코드 수정 대상인 `(group, code)` 조합 없음 |
| 409/CC003 | 같은 그룹에 이미 존재하는 코드 생성 |
| 409/CC004 | 이미 존재하는 그룹 생성 |
| 415/C011 | POST·PATCH의 지원하지 않는 Content-Type |
| 500/C003 | 그 밖의 서버 처리 오류 |
