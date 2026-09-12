# B 계정 테스트용 ETL 소스

이 디렉터리의 Python 파일 세 개는 검증된 ETL 소스의 **바이트 그대로인 테스트 fixture**다. Airbob CI는 비공개 ETL 저장소를 checkout하거나 별도 토큰을 요청하지 않는다. 운영 복원은 계속 봉인 release에 포함된 도구를 사용하며 이 fixture를 사용하지 않는다.

- 원본 파일: 별도 ETL 저장소의 `scripts/growth_accounts.py`, `scripts/growth_runtime.py`, `scripts/growth_settings.py`
- 복사일: 2026-09-12
- 대조한 봉인 실행: `global-b-final-20260912-05`
- 대조한 `release/tool-sources.json` SHA-256: `49c28300d40d9d32a587303f25cce1cbd6f84325b74198ef83b892aa91cf6286`
- ETL B 소스 revision: `e47627aa5ec07640cf7e85a5fd195194ced092e6`. 이 revision에는 이후의 v4 호환 보완이 포함된다. fixture는 위 봉인 실행의 원본 SHA를 유지하므로 해당 revision의 전체 파일과 동일하다는 의미는 아니다.

| 파일 | SHA-256 |
|---|---|
| `growth_accounts.py` | `5e608eb8f6a2a6a5296dcef59094581d2a31b25469ae600f1e0cd10be9d35703` |
| `growth_runtime.py` | `85c61685267a9610353ffe641878f8f5953be543a892edb2d03ebaaf34a3fef3` |
| `growth_settings.py` | `39f22a07ee0732e09cd9bb04a29c338e83276f22333dc7213d1f3a45aa1fc4ee` |

합계 43,079바이트이며 Python 표준 라이브러리와 이 세 파일끼리만 의존한다. 계정 자료, 비밀번호 파일, 환경 파일, SQL, 덤프, JAR는 복사하지 않았다. 테스트가 사용하는 임시 계정은 각 테스트의 임시 디렉터리에 생성된다. 소스를 갱신할 때는 검증된 원본에서 세 파일을 다시 복사하고 `SHA256SUMS`와 이 출처 기록을 함께 검토한다. fixture만 별도로 고치지 않는다.

Python 3.12 이상과 JDK 21이 필요하다. 프로젝트 루트에서 다음 명령으로 검증한다.

```sh
(cd infra/aws/tests/fixtures/etl-account-runtime && sha256sum --check SHA256SUMS)
./gradlew prepareGrowthBContractTestRuntime
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s infra/aws/tests -p 'test_growth_b_*.py' -v
python3 infra/aws/scripts/test_growth_b_publication.py -v
python3 infra/aws/tests/test_global_b_infrastructure.py -v
```

macOS 기본 도구에서는 `sha256sum --check` 대신 `shasum -a 256 --check`를 사용할 수 있다. Gradle task는 Maven Central의 `org.mindrot:jbcrypt:0.4` 한 개만 `build/growth-b-contract-test-runtime/`에 준비한다. Java 앱 컴파일이나 실행, DB 및 Docker 시작 없이 실제 BCrypt의 개별 암호와 교차 암호 거절을 검사하며 JAR 또는 Java가 없으면 실패한다. 인프라 검사는 provider와 backend가 없는 임시 디렉터리에서 Terraform console로 표현식만 평가한다. 이 검사는 AWS 권한이나 실제 배포 성공을 뜻하지 않는다.
