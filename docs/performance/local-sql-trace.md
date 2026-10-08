# 로컬 조회 API의 SQL을 DB 클라이언트에서 확인하기

IntelliJ에서 Gradle 변경을 불러온 뒤 `AirbobApplication`의 Active profiles를
`dev,sql-trace`로 지정하고 다시 실행한다. 평소에는 `dev`만 사용한다.

프로그램 인자의 `--logging.level.org.hibernate.SQL=OFF` 등은 이 기능을 막지 않는다.
출력 로거는 `airbob.sql.trace`이며 Hibernate의 별도 SQL·바인딩 출력은 진단 프로필이 끈다.
기존 `dev` 프로필의 DB 연결과 재고 준비 절차는 그대로 사용한다.

재고 시작 작업이 완료된 뒤 로그인하고 조회 API를 한 번 호출한다.

```http
GET /api/v1/profile/host/accommodations?size=20
```

콘솔에서 `SQL_TRACE`를 찾는다. 출력 형식은 다음과 같다(시간은 예시).

```text
SQL_TRACE GET /api/v1/profile/host/accommodations | JDBC execute 12.345 ms
select ... from accommodation a where a.member_id=6675 and a.status<>'DELETED' order by a.created_at desc,a.id desc limit 21;
```

실제 로그에는 생략하지 않은 SQL이 나온다. SQL 부분만 복사하고, 같은 DB를 연결한
클라이언트에서 앞에 `EXPLAIN ANALYZE`를 붙여 실행한다. `EXPLAIN ANALYZE`는 SELECT를
실제로 실행하므로, 큰 쿼리는 `EXPLAIN FORMAT=JSON`으로 계획부터 확인한다.

SQL의 원래 물음표와 바인딩 값을 따로 맞출 필요가 없다. MySQL 드라이버가 갖고 있는
바인딩 표현을 이용해 문자열 escaping, UUID의 binary 표현, 날짜 커서의 마이크로초를
보존한다. 프로그램의 쿼리 실행은 계속 PreparedStatement를 사용하며 로그 문자열로
업무 SQL을 실행하지 않는다.

출력 범위는 `/api/`의 GET 요청 중 성공한 SELECT다. 시작 시 재고 준비, scheduler,
로그인 POST, Actuator와 DML은 출력하지 않는다. 별도 스레드로 넘긴 비동기 작업에는
요청 컨텍스트가 없을 수 있으므로 모든 비동기 SQL을 포착한다고 보장하지 않는다.

`JDBC execute` 시간은 JDBC execute 호출 구간이며, 결과 전체 읽기·DTO 변환·응답 전송까지
포함한 API 시간은 아니다. 인덱스의 최종 성능 비교에서는 `sql-trace` 프로필을 빼서
로깅 비용을 제외한다. 값이 포함된 진단 로그에는 개인정보가 들어갈 수 있으므로
로컬 분석용으로 다룬다.

P6Spy 의존성은 개발 실행과 테스트에서만 사용하고 운영 `bootJar`에는 포함하지 않는다.
Hikari DataSource와 풀의 Connection은 유지하고 진단 프로필에서 내부 JDBC 드라이버만
계측한다. `aws`, `oci`, `performance-lab`이 함께 활성화된 환경에서는 동작하지 않는다.

근거: [P6Spy 설정](https://p6spy.readthedocs.io/en/latest/configandusage.html),
[P6Spy JDBC 드라이버 방식](https://p6spy.readthedocs.io/en/latest/install.html).
