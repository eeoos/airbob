# Global B를 맥에서 RDS로 적재

초기 SQL 적재는 맥의 MySQL 클라이언트에서 실행한다. `prepare`는 private RDS와 기존
실험 NAT를 준비한 뒤 반환한다. 적재용 Debezium EC2, Linux 적재 도구 실행, 적재 완료를
기다리는 GitHub 작업은 만들지 않는다.

기존 `prepare` 입력에 `B_IMPORT_FROM_MAC=true`를 추가한다. GitHub의 `b_operation`에서는
`{"rdsInstanceClass":"db.m6i.large","importFromMac":true}`로 같은 선택을 전달한다.
RDS는 MySQL 8.4.11, encrypted gp3 100GiB, Single-AZ를 유지한다. 공개 접속은 허용하지
않으며 NAT 보안 그룹에서 오는 3306 연결만 추가한다.

Terraform의 `global_b_mac_import` 출력에 DB identifier, resource ID, endpoint와 NAT
instance ID가 있다. `mac_import_target_ready=true`는 인프라 준비 완료이며 SQL 적재
성공을 뜻하지 않는다. 공개된 기존 preparation manifest는 데이터·용량 참조로만 사용한다.
Mac 실행은 그 manifest의 Linux helper나 이전 host 준비 성공 영수증을 실행·재사용하지 않는다.

## 연결과 실행

1. 맥에 AWS CLI, Session Manager plugin, MySQL 8.4 클라이언트를 준비한다.
2. 기존 NAT에 `AWS-StartPortForwardingSessionToRemoteHost` 세션을 열어 원래 RDS endpoint의
   3306을 맥의 비특권 포트로 전달한다. Session Manager preferences에 최대 세션 시간이
   설정되어 있으면 적재 전에 확인한다. 이 도구는 계정의 세션 정책을 변경하지 않는다.
3. 맥에서 **해당 RDS endpoint 하나만** `127.0.0.1`로 해석되도록 임시 hosts 항목을
   설정한다. MySQL은 원래 endpoint 이름과 전달 포트로 접속하므로 `VERIFY_IDENTITY`와
   AWS CA 검증을 유지한다. importer는 DNS를 바꾸지 않고 loopback 해석을 검사한다.
4. `growth_b_mac_import.py import --help`에 따라 대상 식별자, resource ID, endpoint,
   tunnel port, AWS profile, 봉인 SQL gzip의 경로/SHA, AWS CA의 경로/SHA, 전용 output
   디렉터리와 MySQL 클라이언트 경로를 전달한다. 비밀번호는 입력하지 않는다. 실행기는
   해당 RDS의 managed secret을 조회해 임시 0600 설정 파일을 쓰고 종료 시 지운다.
5. 맥을 전원에 연결하고 네트워크를 유지한다. 실행 명령에 `caffeinate -i`를 사용할 수
   있다. 완료 후 터널과 이번 작업의 hosts 항목을 제거한다.

AWS 문서: [Mac plugin 설치](https://docs.aws.amazon.com/systems-manager/latest/userguide/install-plugin-macos-overview.html),
[원격 호스트 포트 전달](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-sessions-start.html#sessions-remote-port-forwarding),
[세션 최대 시간](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-preferences-max-timeout.html).

## 적재 시간과 완료 경계

SQL import에는 **4시간을 포함한 고정 경과시간 제한이 없다**. 연결 및 개별 조회 timeout은
유지하고, 실제 gzip/MySQL 오류나 사용자 중단을 처리한다. `progress.json`과 표준 출력은
약 30초마다 압축 입력 소비량과 경과시간을 기록한다. 입력 100%는 MySQL 완료를 뜻하지
않으며, 두 프로세스가 모두 exit 0이어야 성공한다. 진행 상태 기록 실패만으로 정상 적재를
종료하지 않는다.

`sql-import-completed.json`은 SQL 성공 직후 디스크에 동기화한다. 이후 점검이 실패하면
`postcheck`로 같은 DB를 확인한다. 완료된 SQL은 다시 적재하지 않는다. 적재 중 실패한
경우도 자동 재시작·중간 SQL 재전송·기존 DB 삭제는 하지 않는다.

Terraform 생성 작업의 실행 제한과 승인된 자원 종료 시각은 SQL 시간 제한과 별개다.
맥의 SQL 적재는 GitHub job 밖에서 실행되지만, 만료된 자원을 자동 연장하지 않는다.
실행 전에는 실제 적재를 수용할 승인 기간이 있어야 한다.

## 적재 후

SQL 적재와 필요한 준비를 마친 뒤 B 스냅샷을 보관하고, 실험 시에는 `db.t3.small`로
축소한다. AWS는 같은 DB의 클래스 변경 시 데이터를 유지한다. 다만 기존 lab 운영기는
생성 시의 클래스를 고정하는 별도 IAM/실행 계약을 사용하므로 **현재 Mac SQL 경로가
자동 축소나 기존 host 기반 서비스·snapshot 절차까지 완료한다고 표현하지 않는다**.
축소는 해당 DB에 대한 변경 계획과 운영기 연결을 마련한 후 실행한다. 두 성능 비교는
같은 작은 사양·같은 초기 데이터에서 수행한다.
