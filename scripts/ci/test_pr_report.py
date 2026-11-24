import json
from pathlib import Path
import tempfile
import unittest

import contracts
import pr_report
from test_contracts import TempRepo, manifest

MAIN = pr_report.MAIN
TEST = pr_report.TEST


def junit(name, cases, time='1.5'):
    body = ''.join(
        f'<testcase name="{case}" classname="{name.split("$")[0]}" time="0.1">{extra}</testcase>'
        for case, extra in cases)
    return f'<?xml version="1.0"?><testsuite name="한글 DisplayName" tests="{len(cases)}" time="{time}">{body}</testsuite>'


class TestSummaryTests(unittest.TestCase):
    def test_counts_failures_errors_skips_nested_classes_and_modules_without_tests(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'settings.gradle').write_text("include 'core:core-api'\ninclude 'clients:client-a'\ninclude 'support:logging'\n")
            results = root / 'core/core-api/build/test-results/test'
            results.mkdir(parents=True)
            (results / 'TEST-a.FooTest.xml').write_text(junit('a.FooTest', [
                ('passes()', ''),
                ('fails()', '<failure message="expected: 1&#10;but was: 2" type="AssertionError">trace</failure>'),
                ('skipped()', '<skipped/>')]))
            (results / 'TEST-a.FooTest$Nested.xml').write_text(junit('a.FooTest$Nested', [
                ('errors()', '<error message="boom" type="IllegalStateException">trace</error>')], time='0.5'))
            (results / 'binary').mkdir()
            client = root / 'clients/client-a/build/test-results/test'
            client.mkdir(parents=True)
            (client / 'TEST-c.ClientTest.xml').write_text(junit('c.ClientTest', [('ok()', '')]))

            summary = pr_report.summarize_tests(root)

        core, client = summary['suites']
        self.assertEqual((core['module'], core['task'], core['tests'], core['failures'], core['errors'], core['skipped']),
                         ('core/core-api', 'test', 4, 1, 1, 1))
        self.assertEqual(core['time'], 2.0)
        self.assertEqual(sorted(core['classes']), ['a.FooTest', 'a.FooTest$Nested'])
        self.assertEqual(client['tests'], 1)
        self.assertEqual(summary['modules_without_tests'], ['support/logging'])
        self.assertEqual(sorted((f['test_class'], f['name'], f['message']) for f in summary['failed']),
                         [('a.FooTest', 'errors()', 'boom'), ('a.FooTest', 'fails()', 'expected: 1')])


class AreaTests(unittest.TestCase):
    def test_paths_map_to_the_template_areas(self):
        cases = {
            MAIN + 'domain/payment/PaymentService.java': 'domain:payment',
            MAIN + 'api/controller/v1/payment/PaymentController.java': 'domain:payment',
            TEST + 'java/kr/kro/airbob/core/domain/coupon/CouponTest.java': 'domain:coupon',
            MAIN + 'api/controller/GlobalExceptionHandler.java': 'api:errors',
            MAIN + 'support/error/ErrorCode.java': 'api:errors',
            MAIN + 'api/config/apidocs/ApiErrors.java': 'api-docs',
            MAIN + 'api/config/RedisConfig.java': 'config',
            MAIN + 'api/security/SessionAuthFilter.java': 'security',
            MAIN + 'support/messaging/outbox/OutboxWriter.java': 'support:messaging',
            'core/core-api/src/main/resources/db/migration/V34__x.sql': 'db-migration',
            TEST + 'java/kr/kro/airbob/migration/V30Test.java': 'db-migration',
            'core/core-api/src/main/resources/application.yaml': 'app-config',
            'core/core-api/src/main/java/kr/kro/airbob/AirbobApplication.java': 'app',
            TEST + 'java/kr/kro/airbob/architecture/LayeredArchitectureTest.java': 'architecture',
            TEST + 'resources/archunit_store/stored.rules': 'architecture',
            'docs/api/openapi/client.yaml': 'api-docs',
            TEST + 'resources/application-test.yaml': 'tests',
            'clients/client-aws-s3/src/main/java/S3StorageClient.java': 'client:aws-s3',
            'support/monitoring/src/main/resources/monitoring.yml': 'support-module:monitoring',
            'settings.gradle': 'build', 'core/core-api/build.gradle': 'build', 'gradle/libs.versions.toml': 'build',
            '.github/workflows/ci.yml': 'ci', 'scripts/ci/scope.py': 'ci',
            'scripts/deploy-oci.sh': 'deploy', 'docker-compose.oci.yml': 'deploy', 'docker/kafka/init-topics.sh': 'deploy',
            'scripts/restore-growth-b-local.py': 'dataset-tools',
            'infra/aws/lab/main.tf': 'infra', 'load-test/k6/a.js': 'load-test',
            'docs/architecture/code-structure.md': 'docs',
            '.claude/rules/payment.md': 'agent-policy', 'CLAUDE.md': 'agent-policy',
            'README.md': 'docs', 'scripts/README.md': 'docs', 'load-test/README.md': 'load-test',
            'Makefile': 'other',
        }
        for path, area in cases.items():
            with self.subTest(path=path):
                self.assertEqual(pr_report.area_of(path), area)

    def test_rule_globs_follow_claude_code_semantics(self):
        cases = [('**/src/**/domain/payment/**/*', MAIN + 'domain/payment/PaymentService.java', True),
                 ('**/src/**/domain/payment/**/*', MAIN + 'domain/payment/gateway/TossAdapter.java', True),
                 ('**/src/**/domain/payment/**/*', MAIN + 'domain/paymentx/A.java', False),
                 ('clients/client-toss-payments/**/*', 'clients/client-toss-payments/build.gradle', True),
                 ('docker-compose*.yml', 'docker-compose.oci.yml', True),
                 ('docker-compose*.yml', 'docker/docker-compose.yml', False),
                 ('Makefile', 'Makefile', True)]
        for glob, path, expected in cases:
            with self.subTest(glob=glob, path=path):
                self.assertEqual(bool(pr_report.glob_regex(glob).match(path)), expected)

    def test_areas_list_the_rule_files_whose_paths_match(self):
        rules = {'payment.md': ['**/src/**/domain/payment/**/*', 'clients/client-toss-payments/**/*'],
                 'messaging.md': ['**/src/**/messaging/**/*']}
        result = pr_report.areas([MAIN + 'domain/payment/messaging/Listener.java', 'docs/a.md'], rules)
        self.assertEqual(result, [
            dict(area='docs', files=['docs/a.md'], rules=[]),
            dict(area='domain:payment', files=[MAIN + 'domain/payment/messaging/Listener.java'],
                 rules=['messaging.md', 'payment.md'])])


SNAPSHOT = '''# Generated by ./gradlew updateOpenApi. Edit Java sources, not this file.
components:
  schemas:
    Foo:
      type: "object"
paths:
  /api/v1/a:
    get:
      description: "read a"
      operationId: "A.read"
    post:
      description: "create a"
      operationId: "A.create"
  /api/v1/b/{id}:
    delete:
      operationId: "B.delete"
servers:
- url: "/"
'''


class OpenApiTests(unittest.TestCase):
    def test_operations_are_read_from_the_paths_section_only(self):
        operations = pr_report.openapi_operations(SNAPSHOT)
        self.assertEqual(sorted(operations), [('DELETE', '/api/v1/b/{id}'), ('GET', '/api/v1/a'), ('POST', '/api/v1/a')])
        self.assertEqual(operations[('GET', '/api/v1/a')][0], 'A.read')
        self.assertEqual(pr_report.openapi_operations(None), {})

    @unittest.skipUnless(Path(pr_report.OPENAPI['client']).is_file(), 'OpenAPI snapshots are not in this checkout')
    def test_every_operation_of_the_real_snapshots_is_found(self):
        for path in pr_report.OPENAPI.values():
            with self.subTest(path=path):
                text = Path(path).read_text()
                self.assertEqual(len(pr_report.openapi_operations(text)),
                                 sum(1 for line in text.splitlines() if line.startswith('      operationId: ')))


ERROR_CODE = '''public enum ErrorCode {
	// R018 (quote expiry) is retired; do not reuse it.
	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "M001", "없는 사용자입니다."),
	RESERVATION_INVENTORY_BUSY(
		HttpStatus.SERVICE_UNAVAILABLE,
		"R025",
		"잠시 후 다시 시도해 주세요; 재고 확인 중"),
	QUOTED("x", HttpStatus.BAD_REQUEST),
	LAST(HttpStatus.FORBIDDEN, "S004", "마지막 \\"코드\\"");
}'''


class ErrorCodeTests(unittest.TestCase):
    def test_multi_line_constants_semicolons_in_messages_and_escapes_are_parsed(self):
        codes = pr_report.error_codes(ERROR_CODE)
        self.assertEqual(sorted(codes), ['LAST', 'MEMBER_NOT_FOUND', 'RESERVATION_INVENTORY_BUSY'])
        self.assertEqual(codes['RESERVATION_INVENTORY_BUSY'],
                         dict(status='SERVICE_UNAVAILABLE', code='R025', message='잠시 후 다시 시도해 주세요; 재고 확인 중'))

    @unittest.skipUnless(Path(pr_report.ERROR_CODES).is_file(), 'ErrorCode is not in this checkout')
    def test_every_real_error_code_is_found(self):
        text = Path(pr_report.ERROR_CODES).read_text()
        self.assertEqual(len(pr_report.error_codes(text)), text.count('HttpStatus.'))


class FactsTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.repo = TempRepo(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def base_commit(self):
        r = self.repo
        r.write('settings.gradle', "include 'core:core-api'\n")
        r.write('gradle/libs.versions.toml', '[versions]\nspring-boot = "3.5.8"\n\n[libraries]\nx = { module = "a:b" }\n')
        r.write(pr_report.OPENAPI['client'], SNAPSHOT)
        r.write(pr_report.ERROR_CODES, ERROR_CODE)
        r.write(contracts.MIGRATION_DIR + 'V1__init.sql', 'create table a (id bigint);')
        r.write(pr_report.ARCHUNIT_STORE + 'stored.rules', '#\nDomain\\ dependencies\\ should\\ be\\ free\\ of\\ cycles=cycles-file\n')
        r.write(pr_report.ARCHUNIT_STORE + 'cycles-file', 'Domain dependency cycle edge: payment -> reservation\n')
        r.write('core/core-api/src/main/resources/application-oci.yaml', 'a: ${KNOWN}\nb: ${OPTIONAL:x}\n')
        r.write(contracts.ARCHIVE_MANIFEST, manifest(('load-test/a.js', 'a')))
        r.write('load-test/a.js', 'a')
        r.write('.claude/rules/payment.md', '---\npaths:\n  - "**/src/**/domain/payment/**/*"\n---\n# Payment\n')
        return r.commit('base')

    def test_signals_report_what_changed_between_base_and_head(self):
        base = self.base_commit()
        r = self.repo
        r.write('settings.gradle', "include 'core:core-api'\ninclude 'clients:client-new'\n")
        r.write('gradle/libs.versions.toml', '[versions]\nspring-boot = "3.5.9"\n\n[libraries]\nx = { module = "a:b" }\n')
        r.write(pr_report.OPENAPI['client'], SNAPSHOT.replace('read a', 'read a v2').replace(
            '  /api/v1/b/{id}:\n    delete:\n      operationId: "B.delete"\n', '  /api/v1/c:\n    put:\n      operationId: "C.put"\n'))
        r.write(pr_report.ERROR_CODES, ERROR_CODE.replace('"M001"', '"M009"').replace(
            'LAST(', 'NEW_CODE(HttpStatus.CONFLICT, "S004", "재사용"),\n\tLAST2('))
        r.write(contracts.MIGRATION_DIR + 'V1__init.sql', 'create table a (id bigint, b int);')
        r.write(contracts.MIGRATION_DIR + 'V1__again.sql', 'select 1;')
        r.write(contracts.MIGRATION_DIR + 'V2__add_b.sql', 'create table b (id bigint);')
        r.write(pr_report.ARCHUNIT_STORE + 'cycles-file',
                'Domain dependency cycle edge: payment -> reservation\nDomain dependency cycle edge: review -> search\n')
        r.write('core/core-api/src/main/resources/application-oci.yaml', 'a: ${KNOWN}\nb: ${OPTIONAL:x}\nc: ${NEW_SECRET}\n')
        r.write('load-test/a.js', 'edited')
        r.write(MAIN + 'domain/payment/PaymentService.java', 'class PaymentService {}')
        head = r.commit('head')

        facts = pr_report.collect_facts(base, head, cwd=r.root)
        signals = facts['contracts']

        self.assertEqual(signals['api']['client']['added'], ['PUT /api/v1/c (C.put)'])
        self.assertEqual(signals['api']['client']['removed'], ['DELETE /api/v1/b/{id} (B.delete)'])
        self.assertEqual(signals['api']['client']['changed'], ['GET /api/v1/a (A.read)'])
        self.assertEqual(signals['api']['admin'], dict(operations=0, added=[], removed=[], changed=[]))
        self.assertEqual(signals['migrations']['violations'],
                         [contracts.MIGRATION_DIR + 'V1__init.sql: applied migrations are roll-forward only (M)'])
        self.assertEqual(signals['migrations']['problems'], [
            'V1__again.sql: version is not greater than the base maximum V1'])
        self.assertEqual(signals['migrations']['added'], ['V1__again.sql', 'V2__add_b.sql'])
        self.assertEqual(signals['error_codes']['added'], ['LAST2', 'NEW_CODE'])
        self.assertEqual(signals['error_codes']['removed'], ['LAST'])
        self.assertEqual(signals['error_codes']['changed'],
                         [dict(name='MEMBER_NOT_FOUND', field='code', before='M001', after='M009')])
        self.assertEqual(signals['error_codes']['reassigned'], ['S004: LAST → LAST2', 'S004: LAST → NEW_CODE'])
        cycles = signals['archunit']['rules'][0]
        self.assertEqual((cycles['label'], cycles['before'], cycles['after'], cycles['added']),
                         ('도메인 순환 간선', 1, 2, ['Domain dependency cycle edge: review -> search']))
        self.assertEqual(signals['archive']['problems'], ['load-test/a.js: content differs from the recorded restored_sha256'])
        self.assertEqual(signals['config']['files'][0]['new_required'], ['NEW_SECRET'])
        self.assertEqual(signals['build'], dict(modules_added=['clients/client-new'], modules_removed=[],
                                                versions=[dict(name='spring-boot', before='3.5.8', after='3.5.9')]))
        payment = next(a for a in facts['areas'] if a['area'] == 'domain:payment')
        self.assertEqual(payment['rules'], ['payment.md'])

        report = pr_report.render(facts)
        self.assertTrue(report.startswith(pr_report.MARKER + '\n'))
        for expected in ('| API (OpenAPI 스냅샷) | client +1 −1 ~1 (operation client 3, admin 0) |',
                         '| 보관 부하 테스트 | ⚠️ 계약 위반 1건(CI 실패 대상) |',
                         '| ArchUnit 동결 | 도메인 순환 간선 1 → 2 ⚠️ |',
                         '| 빌드·모듈 | 모듈 추가 `clients/client-new`, spring-boot 3.5.8 → 3.5.9 |',
                         '⚠️ 새 필수 환경변수 `NEW_SECRET` (application-oci.yaml)'):
            self.assertIn(expected, report)

    def test_unchanged_contracts_and_a_broken_section_are_reported_without_failing(self):
        base = self.base_commit()
        self.repo.write('docs/notes.md', 'notes')
        head = self.repo.commit('docs only')
        facts = pr_report.collect_facts(base, head, cwd=self.repo.root)
        report = pr_report.render(facts)
        for expected in ('| DB 마이그레이션 | 변경 없음 (base 최신 V1) |', '| 오류 코드 | 변경 없음 (3개) |',
                         '| 보관 부하 테스트 | 기록된 해시와 일치 |', '| 설정 | 변경 없음 |', '| 빌드·모듈 | 변경 없음 |'):
            self.assertIn(expected, report)

        facts['contracts']['api'] = pr_report.safely(lambda: 1 / 0)
        self.assertIn('| API (OpenAPI 스냅샷) | ⚠️ 수집 실패: ZeroDivisionError: division by zero |', pr_report.render(facts))


class CiAndRenderTests(unittest.TestCase):
    def needs(self, app_result='success'):
        return {'scope': {'result': 'success', 'outputs': {'app': 'true', 'infrastructure': 'false', 'data': 'false', 'api_docs': 'false'}},
                'app': {'result': app_result}, 'infrastructure': {'result': 'skipped'}, 'data': {'result': 'skipped'},
                'api_docs': {'result': 'skipped'}}

    def test_ci_jobs_predict_the_required_check(self):
        self.assertIsNone(pr_report.ci_jobs(self.needs())['verdict'])
        failed = pr_report.ci_jobs(self.needs('failure'))
        self.assertEqual(failed['verdict'], 'app: expected success, observed failure')
        self.assertIn('| app | 예 | failure ⚠️ |', pr_report.render_ci(failed))

    def facts(self, failed=0, files=1):
        tests = dict(schema=1, modules_without_tests=[], suites=[dict(
            module='core/core-api', task='test', tests=10, failures=failed, errors=0, skipped=0, time=1.0,
            classes={'a.SlowTest': dict(tests=10, failures=failed, skipped=0, time=1.0)})],
            failed=[dict(module='core/core-api', task='test', test_class='a.SlowTest', name=f'case{i}()', message='x' * 150)
                    for i in range(failed)])
        empty = dict(added=[], removed=[], changed=[], operations=0)
        return dict(schema=1, pr=1, base='b' * 40, head='h' * 40, pr_head='p' * 40, merge_base='m' * 40,
                    changed=[dict(status='A', path=f'docs/{i}.md') for i in range(files)],
                    scope={}, ci=pr_report.ci_jobs(self.needs()), tests=tests,
                    areas=[dict(area='docs', files=[f'docs/{i}.md' for i in range(files)], rules=[])],
                    contracts=dict(api=dict(client=empty, admin=empty),
                                   migrations=dict(base_latest=33, added=[], problems=[], violations=[]),
                                   error_codes=dict(count=98, added=[], removed=[], changed=[], reassigned=[]),
                                   archunit=dict(rules=[], stored_rules_changed=False, properties_changed=False),
                                   archive=dict(problems=[], changed=[]), config=dict(files=[]),
                                   build=dict(modules_added=[], modules_removed=[], versions=[])))

    def test_failed_tests_are_listed_and_marked(self):
        report = pr_report.render(self.facts(failed=2))
        self.assertIn('| core/core-api | test | 10 | 2 ⚠️ | 0 | 1.0 |', report)
        self.assertIn('<details><summary>⚠️ 실패한 테스트 2개</summary>', report)
        self.assertIn('head `pppppppp`', report)

    def test_missing_summary_explains_why(self):
        facts = self.facts()
        facts['tests'] = None
        self.assertIn('⚠️ 테스트 요약 없음', pr_report.render(facts))
        facts['ci']['rows'][1]['selected'] = 'false'
        self.assertIn('앱 테스트 미실행(scope가 app 잡을 고르지 않음).', pr_report.render(facts))

    def test_long_reports_are_compacted_then_cut_to_the_comment_limit(self):
        facts = self.facts(failed=400, files=3000)
        full = pr_report.render(facts)
        compact = pr_report.render(facts, compact=True)
        self.assertGreater(len(full), len(compact))
        self.assertEqual(pr_report.render_within_limit(facts, limit=len(compact)), compact)
        cut = pr_report.render_within_limit(facts, limit=1000)
        self.assertLessEqual(len(cut), 1000)
        self.assertTrue(cut.startswith(pr_report.MARKER))
        self.assertIn('보고서가 너무 길어 잘랐습니다', cut)
        self.assertEqual(pr_report.render_within_limit(self.facts()), pr_report.render(self.facts()))

    def test_facts_round_trip_through_json(self):
        facts = self.facts(failed=1)
        self.assertEqual(json.loads(json.dumps(facts, ensure_ascii=False)), facts)


if __name__ == '__main__':
    unittest.main()
