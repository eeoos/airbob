# 쿠폰 커넥션 풀 10·20·30의 로컬 비교 근거

원본 계측 파일·설정·검증 기록: [측정 당시 보관본](https://github.com/eeoos/airbob/tree/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/coupon-pool-sizing-2026-10-10).

[상세 보고서](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/coupon-skip-locked-local-results.md#풀-102030-비교--2026-10-10)의 원본 자료다.
실행 ID는 `local-20261010-172347-6daa`이며 환경은 로컬 공유 호스트다.
대리 키 RR, 앱 1개, 같은 JAR·DB 설정에서 maximumPoolSize/minimumIdle만 10/20/30으로 바꿨다.
500/1,000/1,500 RPS를 20초씩 3회, 같은 초기 재고 30,001개로 측정했다.

| 파일 | 내용 |
|---|---|
| `comparison.json` | 실행 조건, 풀별 3회 결과, 모든 본 측정·워밍업, 정리 결과와 원본 해시 |
| `raw.ndjson.gz` | 클라이언트·SQL·풀·DB·호스트·재고·정리 원본의 허용 목록 |
| `harness-sources.ndjson.gz` | 실행 시작에 고정한 k6/Python 소스·입력·문서와 파일별 SHA-256 |
| `validation.json` | 애플리케이션 빌드 및 Python/k6 계약 테스트 범위·로그 해시 |
| `SHA256SUMS` | 이 디렉터리의 파일별 해시(`SHA256SUMS` 자신 제외) |

본 측정 27회 중 11회 유효·10회 전체 기준 통과다. 실제 요청 475,697건은 모두 발급됐고
고유 회원 수·잔여 재고가 맞았다. 행 잠금 대기·교착 상태·중복·초과 발급·CP012는 관측되지 않았다.
요청 누락 9회, 호스트 CPU 기준 초과 14회가 있으며 7회는 두 문제가 겹친다.
부하 누락 및 무효 회차를 삭제하거나 통과한 결과만으로 개선율을 계산하지 않았다.

두 압축 묶음은 NDJSON이며 각 줄의 `path`, `sha256`, `text`로 원본을 확인할 수 있다.
해시는 UTF-8 원본 바이트 기준이다. `comparison.json`의 `sourceArtifactSha256`은 원본 파일별 해시,
`metadata.harnessSha256`은 실행 소스 해시다. 앱 바이너리 자체는 저장소에 넣지 않았다.
앱 JAR SHA-256은 `e84067d904eecf5d9ab3af33cbf43f7d75ed1f25ac4e77b4eae876fed1eb1b72`다.
이전 풀 10 고정 실험과 바이너리·초기 재고·재시작 조건이 달라 통계를 합치지 않는다.

풀과 RPS 순서는 회전했고 풀 차례마다 앱을 재시작했다. 각 본 측정 전 100 RPS·10초 워밍업을 수행했지만
JIT·캐시·이력 누적·공유 호스트 영향이 모두 제거됐다는 뜻은 아니다. 특히 1,500 RPS는 모든 풀에서
전체 기준을 반복 통과하지 못했다. 풀 20/30의 운영 최적값이나 5,000 RPS 가능성을 확정할 수 없다.

계정 비밀번호·벤치마크 토큰·세션·런타임 설정·애플리케이션 로그·로그인 기록은 이 묶음에서 제외했다.
임시 앱과 MySQL/Redis는 정리했으며 기존 개발용 컨테이너는 유지했다. AWS 자원은 사용하지 않았다.
