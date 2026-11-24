"""Deterministic PR report: CI jobs, test results, changed areas, and contract signals (no AI).

  tests  JUnit XML of every Gradle module -> test-summary.json (the app job runs this)
  facts  facts.json + report.md for a change (the report job runs this; works locally with --base/--head)

Every value comes from Git, JUnit XML, or the CI `needs` context, so the report can be checked
against its sources. A section that cannot be collected is reported as such instead of failing.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

import check_result
import contracts
import scope

MARKER = '<!-- airbob-pr-report -->'
COMMENT_LIMIT = 65000
SCHEMA = 1

MAIN = 'core/core-api/src/main/java/kr/kro/airbob/core/'
TEST = 'core/core-api/src/test/'
OPENAPI = {'client': 'docs/api/openapi/client.yaml', 'admin': 'docs/api/openapi/admin.yaml'}
ERROR_CODES = MAIN + 'support/error/ErrorCode.java'
ARCHUNIT_STORE = TEST + 'resources/archunit_store/'
ARCHUNIT_PROPERTIES = TEST + 'resources/archunit.properties'
SETTINGS = 'settings.gradle'
VERSIONS = 'gradle/libs.versions.toml'
CONFIG_FILE = re.compile(r'(?:core/core-api/src/main/resources/application[^/]*\.ya?ml'
                         r'|core/core-api/src/test/resources/application-test\.ya?ml'
                         r'|support/[^/]+/src/main/resources/[^/]+\.ya?ml)$')
MIGRATION_NAME = re.compile(r'V(\d+)__[a-z0-9_]+\.sql$')
INCLUDE = re.compile(r"^\s*include\s+'([^']+)'", re.M)
VERSION_LINE = re.compile(r'^([A-Za-z0-9_.-]+)\s*=\s*"([^"]*)"\s*$')
REQUIRED_PLACEHOLDER = re.compile(r'\$\{([A-Z0-9_]+)\}')
ERROR_CODE = re.compile(
    r'^\s*([A-Z][A-Z0-9_]*)\s*\(\s*HttpStatus\.([A-Z_]+)\s*,\s*"([^"]*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)\s*[,;]', re.M)
OPENAPI_PATH = re.compile(r'^  "?(/[^"]*)"?:$')
OPENAPI_METHOD = re.compile(r'^    (get|put|post|delete|patch|head|options|trace):$')
OPENAPI_OPERATION_ID = re.compile(r'^      operationId: "(.*)"$')
RULE_GLOB = re.compile(r'^\s*-\s*"([^"]+)"\s*$')
ARCHUNIT_LABELS = {
    'Classes should not use repositories owned by another domain': '다른 도메인 Repository 사용',
    'Domain dependencies should be free of cycles': '도메인 순환 간선',
    'Entities and embeddables should not depend on web DTOs, controllers, or services': '엔티티의 DTO·서비스 의존',
}
CI_JOBS = ('app', 'infrastructure', 'data', 'api_docs')


# ---------------------------------------------------------------- test results

def gradle_modules(settings_text):
    return [include.replace(':', '/') for include in INCLUDE.findall(settings_text)]


def first_line(text, limit=200):
    line = (text or '').strip().splitlines()[0] if (text or '').strip() else ''
    return line if len(line) <= limit else line[:limit - 1] + '…'


def summarize_tests(root='.'):
    """Per module and Gradle test task: counts, per-class totals, and failed cases."""
    root = Path(root)
    suites, failed, without = [], [], []
    for module in gradle_modules((root / SETTINGS).read_text()):
        results = root / module / 'build' / 'test-results'
        tasks = sorted(path for path in results.iterdir() if path.is_dir()) if results.is_dir() else []
        reports = [(task.name, sorted(task.glob('TEST-*.xml'))) for task in tasks]
        reports = [(task, files) for task, files in reports if files]
        if not reports:
            without.append(module)
            continue
        for task, files in reports:
            suite = dict(module=module, task=task, tests=0, failures=0, errors=0, skipped=0, time=0.0, classes={})
            for file in files:
                xml = ET.parse(file).getroot()
                # The file name is the test class (also for @Nested); <testsuite name> may be a @DisplayName.
                name = file.name[len('TEST-'):-len('.xml')]
                entry = suite['classes'].setdefault(name, dict(tests=0, failures=0, skipped=0, time=0.0))
                entry['time'] += float(xml.get('time') or 0)
                for case in xml.iter('testcase'):
                    entry['tests'] += 1
                    problem = case.find('failure')
                    kind = 'failures'
                    if problem is None:
                        problem, kind = case.find('error'), 'errors'
                    if problem is not None:
                        suite[kind] += 1
                        entry['failures'] += 1
                        failed.append(dict(module=module, task=task, test_class=case.get('classname') or name,
                                           name=case.get('name') or '',
                                           message=first_line(problem.get('message') or problem.text)))
                    elif case.find('skipped') is not None:
                        suite['skipped'] += 1
                        entry['skipped'] += 1
            suite['tests'] = sum(entry['tests'] for entry in suite['classes'].values())
            suite['time'] = round(sum(entry['time'] for entry in suite['classes'].values()), 3)
            for entry in suite['classes'].values():
                entry['time'] = round(entry['time'], 3)
            suites.append(suite)
    return dict(schema=SCHEMA, suites=suites, failed=failed, modules_without_tests=without)


# ---------------------------------------------------------------- git access

class Repo:
    """Reads files at commits through Git, so the head need not be checked out."""

    def __init__(self, cwd='.'):
        self.cwd = str(cwd)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', self.cwd, *args], stderr=subprocess.PIPE, timeout=60).decode()

    def show(self, revision, path):
        try:
            return self.git('show', f'{revision}:{path}')
        except subprocess.CalledProcessError:
            return None

    def ls(self, revision, prefix):
        listing = self.git('ls-tree', '-r', '--name-only', '-z', revision, '--', prefix)
        return [path for path in listing.split('\0') if path]

    def merge_base(self, base, head):
        return self.git('merge-base', base, head).strip()

    def name_status(self, comparison):
        return contracts.name_status(comparison, lambda *args: self.git(*args))


# ---------------------------------------------------------------- areas

def area_of(path):
    match = re.match(re.escape(MAIN) + r'(?:api/controller/v1|domain)/([a-z0-9]+)/', path)
    if match:
        return 'domain:' + match.group(1)
    if path == MAIN + 'api/controller/GlobalExceptionHandler.java' or path.startswith(MAIN + 'support/error/'):
        return 'api:errors'
    if path.startswith(MAIN + 'api/config/apidocs/'):
        return 'api-docs'
    if path.startswith(MAIN + 'api/config/'):
        return 'config'
    if path.startswith(MAIN + 'api/security/'):
        return 'security'
    match = re.match(re.escape(MAIN) + r'support/([a-z0-9]+)/', path)
    if match:
        return 'support:' + match.group(1)
    if path.startswith(('core/core-api/src/main/resources/db/migration/', TEST + 'java/kr/kro/airbob/migration/')):
        return 'db-migration'
    if path.startswith('core/core-api/src/main/resources/'):
        return 'app-config'
    if path.startswith('core/core-api/src/main/java/'):
        return 'app'
    if path.startswith((TEST + 'java/kr/kro/airbob/architecture/', TEST + 'resources/archunit')):
        return 'architecture'
    if path.startswith((TEST + 'java/kr/kro/airbob/apidocs/', TEST + 'resources/apidocs/', 'docs/api/')):
        return 'api-docs'
    match = re.match(re.escape(TEST) + r'java/kr/kro/airbob/core/(?:api/controller/v1|domain)/([a-z0-9]+)/', path)
    if match:
        return 'domain:' + match.group(1)
    if path.startswith(TEST):
        return 'tests'
    match = re.match(r'clients/client-([a-z0-9-]+)/', path)
    if match:
        return 'client:' + match.group(1)
    match = re.match(r'support/([a-z0-9-]+)/', path)
    if match:
        return 'support-module:' + match.group(1)
    if scope.is_build_file(path) or path.startswith('gradle/') or path in ('gradlew', 'gradlew.bat'):
        return 'build'
    if path.startswith(('.github/', 'scripts/ci/')):
        return 'ci'
    if path in scope.DEPLOYMENT_SCRIPTS or path.startswith(('docker-compose', 'docker/', 'monitoring/')):
        return 'deploy'
    if path.startswith('scripts/archive/') or (path.startswith('scripts/') and path.endswith('.py')):
        return 'dataset-tools'
    if path.startswith('infra/'):
        return 'infra'
    if path.startswith('load-test/'):
        return 'load-test'
    if path.startswith('docs/'):
        return 'docs'
    if path.startswith('.claude/') or path in ('CLAUDE.md', 'AGENTS.md'):
        return 'agent-policy'
    return 'other'


def rule_globs(repo, revision):
    """`paths:` globs of each .claude/rules/*.md front matter."""
    rules = {}
    for path in repo.ls(revision, '.claude/rules/'):
        if not path.endswith('.md'):
            continue
        text = repo.show(revision, path) or ''
        lines = text.splitlines()
        if not lines or lines[0].strip() != '---':
            continue
        globs = []
        for line in lines[1:]:
            if line.strip() == '---':
                break
            match = RULE_GLOB.match(line)
            if match:
                globs.append(match.group(1))
        rules[path.rsplit('/', 1)[-1]] = globs
    return rules


def glob_regex(glob):
    """Claude Code `paths:` globs: `**/` is zero or more directories, `*` and `?` stay within one segment."""
    pattern, i = '', 0
    while i < len(glob):
        if glob.startswith('**/', i):
            pattern, i = pattern + '(?:.*/)?', i + 3
        elif glob.startswith('**', i):
            pattern, i = pattern + '.*', i + 2
        elif glob[i] == '*':
            pattern, i = pattern + '[^/]*', i + 1
        elif glob[i] == '?':
            pattern, i = pattern + '[^/]', i + 1
        else:
            pattern, i = pattern + re.escape(glob[i]), i + 1
    return re.compile(pattern + r'\Z')


def areas(paths, rules):
    grouped = {}
    for path in paths:
        grouped.setdefault(area_of(path), []).append(path)
    compiled = {rule: [glob_regex(glob) for glob in globs] for rule, globs in rules.items()}
    result = []
    for area in sorted(grouped):
        files = sorted(grouped[area])
        matched = sorted(rule for rule, patterns in compiled.items()
                         if any(pattern.match(path) for path in files for pattern in patterns))
        result.append(dict(area=area, files=files, rules=matched))
    return result


# ---------------------------------------------------------------- contract signals

def openapi_operations(text):
    """(METHOD, path) -> [operationId, digest of the operation block] from a key-sorted snapshot."""
    operations = {}
    if text is None:
        return operations
    in_paths, path, current, block, operation_id = False, None, None, [], None

    def close():
        if current:
            operations[current] = [operation_id, hashlib.sha1('\n'.join(block).encode()).hexdigest()]

    for line in text.splitlines():
        if not line.startswith(' '):
            close()
            current, in_paths, path = None, line == 'paths:', None
            continue
        if not in_paths:
            continue
        match = OPENAPI_PATH.match(line)
        if match:
            close()
            current, path = None, match.group(1)
            continue
        match = OPENAPI_METHOD.match(line)
        if match and path:
            close()
            current, block, operation_id = (match.group(1).upper(), path), [line], None
            continue
        if current:
            block.append(line)
            match = OPENAPI_OPERATION_ID.match(line)
            if match:
                operation_id = match.group(1)
    close()
    return operations


def api_signal(repo, base, head):
    groups = {}
    for group, path in OPENAPI.items():
        before, after = openapi_operations(repo.show(base, path)), openapi_operations(repo.show(head, path))

        def label(key, operations):
            return f'{key[0]} {key[1]} ({operations[key][0]})'

        groups[group] = dict(
            operations=len(after),
            added=[label(key, after) for key in sorted(after.keys() - before.keys())],
            removed=[label(key, before) for key in sorted(before.keys() - after.keys())],
            changed=[label(key, after) for key in sorted(after.keys() & before.keys()) if after[key] != before[key]])
    return groups


def migration_signal(repo, base, entries):
    changed = [(status, path) for status, path in entries if path.startswith(contracts.MIGRATION_DIR)]
    versions = [int(m.group(1)) for m in (MIGRATION_NAME.search(p) for p in repo.ls(base, contracts.MIGRATION_DIR)) if m]
    latest = max(versions, default=0)
    added, problems = [], []
    for status, path in changed:
        if status != 'A':
            continue
        name = path.rsplit('/', 1)[-1]
        added.append(name)
        match = MIGRATION_NAME.fullmatch(name)
        if not match:
            problems.append(f'{name}: name does not match V<n>__lower_snake.sql')
        elif int(match.group(1)) <= latest:
            problems.append(f'{name}: version is not greater than the base maximum V{latest}')
    added_versions = [MIGRATION_NAME.fullmatch(n).group(1) for n in added if MIGRATION_NAME.fullmatch(n)]
    for version in sorted({v for v in added_versions if added_versions.count(v) > 1}, key=int):
        problems.append(f'V{version}: added more than once')
    return dict(base_latest=latest, added=sorted(added), problems=problems,
                violations=contracts.applied_migration_violations(entries))


def error_codes(text):
    return {name: dict(status=status, code=code, message=message)
            for name, status, code, message in ERROR_CODE.findall(text or '')}


def error_code_signal(repo, base, head):
    before, after = error_codes(repo.show(base, ERROR_CODES)), error_codes(repo.show(head, ERROR_CODES))
    changed = []
    for name in sorted(before.keys() & after.keys()):
        for field in ('status', 'code', 'message'):
            if before[name][field] != after[name][field]:
                changed.append(dict(name=name, field=field, before=before[name][field], after=after[name][field]))
    owners_before = {value['code']: name for name, value in before.items()}
    reassigned = sorted(f'{code}: {owners_before[code]} → {name}' for name, value in after.items()
                        for code in [value['code']] if code in owners_before and owners_before[code] != name)
    return dict(count=len(after), added=sorted(after.keys() - before.keys()),
                removed=sorted(before.keys() - after.keys()), changed=changed, reassigned=reassigned)


def archunit_rules(text):
    rules = {}
    for line in (text or '').splitlines():
        if not line.strip() or line.lstrip().startswith('#') or '=' not in line:
            continue
        key, file = line.split('=', 1)
        rules[re.sub(r'\\(.)', r'\1', key.strip())] = file.strip()
    return rules


def archunit_signal(repo, base, head, paths):
    def violations(revision):
        rules = archunit_rules(repo.show(revision, ARCHUNIT_STORE + 'stored.rules'))
        return {rule: [line for line in (repo.show(revision, ARCHUNIT_STORE + file) or '').splitlines() if line.strip()]
                for rule, file in rules.items()}

    before, after = violations(base), violations(head)
    rules = []
    for rule in sorted(before.keys() | after.keys()):
        old, new = before.get(rule, []), after.get(rule, [])
        rules.append(dict(rule=rule, label=ARCHUNIT_LABELS.get(rule, rule), before=len(old), after=len(new),
                          added=sorted(set(new) - set(old)), removed=sorted(set(old) - set(new))))
    return dict(rules=rules,
                stored_rules_changed=ARCHUNIT_STORE + 'stored.rules' in paths,
                properties_changed=ARCHUNIT_PROPERTIES in paths)


def archive_signal(head, paths, cwd='.'):
    return dict(problems=contracts.archive_mismatches(contracts.RevisionFiles(head, cwd)),
                changed=sorted(path for path in paths if path.startswith(contracts.ARCHIVE_DIR)))


def config_signal(repo, base, head, paths):
    files = []
    for path in sorted(p for p in paths if CONFIG_FILE.match(p)):
        before = set(REQUIRED_PLACEHOLDER.findall(repo.show(base, path) or ''))
        after = set(REQUIRED_PLACEHOLDER.findall(repo.show(head, path) or ''))
        files.append(dict(path=path, new_required=sorted(after - before), dropped_required=sorted(before - after)))
    return dict(files=files)


def toml_versions(text):
    versions, section = {}, None
    for line in (text or '').splitlines():
        stripped = line.strip()
        if stripped.startswith('['):
            section = stripped
            continue
        match = VERSION_LINE.match(stripped)
        if section == '[versions]' and match:
            versions[match.group(1)] = match.group(2)
    return versions


def build_signal(repo, base, head):
    before = set(gradle_modules(repo.show(base, SETTINGS) or ''))
    after = set(gradle_modules(repo.show(head, SETTINGS) or ''))
    old, new = toml_versions(repo.show(base, VERSIONS)), toml_versions(repo.show(head, VERSIONS))
    versions = [dict(name=name, before=old.get(name), after=new.get(name))
                for name in sorted(old.keys() | new.keys()) if old.get(name) != new.get(name)]
    return dict(modules_added=sorted(after - before), modules_removed=sorted(before - after), versions=versions)


# ---------------------------------------------------------------- facts

def safely(collect):
    try:
        return collect()
    except Exception as error:  # a broken section must not hide the others
        return dict(error=f'{type(error).__name__}: {first_line(str(error))}')


def ci_jobs(needs):
    if needs is None:
        return None
    outputs = needs.get('scope', {}).get('outputs', {})
    rows = [dict(job='scope', selected=None, result=needs.get('scope', {}).get('result'))]
    for job in CI_JOBS:
        rows.append(dict(job=job, selected=outputs.get(job), result=needs.get(job, {}).get('result')))
    try:
        check_result.verify(needs)
        verdict = None
    except (ValueError, KeyError) as error:
        verdict = first_line(str(error)) or type(error).__name__
    return dict(rows=rows, verdict=verdict)


def collect_facts(base, head, needs=None, tests=None, pr=None, pr_head=None, cwd='.'):
    repo = Repo(cwd)
    merge_base = repo.merge_base(base, head)
    entries = repo.name_status(f'{base}...{head}')
    paths = [path for _, path in entries]
    rules = safely(lambda: rule_globs(repo, head))
    return dict(
        schema=SCHEMA, pr=pr, base=base, head=head, pr_head=pr_head, merge_base=merge_base,
        changed=[dict(status=status, path=path) for status, path in entries],
        scope=scope.classify(paths),
        ci=ci_jobs(needs),
        tests=tests,
        areas=safely(lambda: areas(paths, rules if 'error' not in rules else {})),
        contracts=dict(
            api=safely(lambda: api_signal(repo, merge_base, head)),
            migrations=safely(lambda: migration_signal(repo, merge_base, entries)),
            error_codes=safely(lambda: error_code_signal(repo, merge_base, head)),
            archunit=safely(lambda: archunit_signal(repo, merge_base, head, set(paths))),
            archive=safely(lambda: archive_signal(head, paths, cwd)),
            config=safely(lambda: config_signal(repo, merge_base, head, paths)),
            build=safely(lambda: build_signal(repo, merge_base, head)),
        ))


# ---------------------------------------------------------------- rendering

WARN = '⚠️'


def short(sha):
    return (sha or '')[:8]


def code(text):
    return '`' + str(text).replace('`', "'") + '`'


def inline(items, limit=5):
    """A table cell listing at most `limit` items."""
    shown = ', '.join(items[:limit])
    return shown + (f' 외 {len(items) - limit}개' if len(items) > limit else '')


def details(summary, lines, limit=30):
    if not lines:
        return []
    shown = lines[:limit] + ([f'- … 외 {len(lines) - limit}개'] if len(lines) > limit else [])
    return ['', f'<details><summary>{summary}</summary>', '', *shown, '', '</details>']


def render_ci(ci):
    if ci is None:
        return ['CI 잡 정보 없음(로컬 실행).']
    names = {True: '예', False: '아니오'}
    lines = ['| 잡 | 선택 | 결과 |', '|---|---|---|']
    for row in ci['rows']:
        selected = '-' if row['selected'] is None else names.get(row['selected'] == 'true', row['selected'])
        expected = None if row['selected'] is None else ('success' if row['selected'] == 'true' else 'skipped')
        mark = f' {WARN}' if (expected and row['result'] != expected) or (row['job'] == 'scope' and row['result'] != 'success') else ''
        lines.append(f"| {row['job']} | {selected} | {row['result']}{mark} |")
    lines.append('')
    lines.append('`CI result` 예상: 통과' if ci['verdict'] is None else f"`CI result` 예상: {WARN} 실패 ({ci['verdict']})")
    return lines


def render_tests(tests, ci, compact):
    if tests is None:
        app_selected = ci and next((r['selected'] for r in ci['rows'] if r['job'] == 'app'), None)
        if app_selected == 'false':
            return ['앱 테스트 미실행(scope가 app 잡을 고르지 않음).']
        return [f'{WARN} 테스트 요약 없음(빌드가 요약 전에 멈췄거나 요약을 만들지 못함).']
    if 'error' in tests:
        return [f"{WARN} 테스트 요약 수집 실패: {tests['error']}"]
    lines = ['| 모듈 | 태스크 | 테스트 | 실패 | 건너뜀 | 시간(초) |', '|---|---|---:|---:|---:|---:|']
    total = dict(tests=0, failures=0, skipped=0)
    for suite in tests['suites']:
        failures = suite['failures'] + suite['errors']
        total['tests'] += suite['tests']
        total['failures'] += failures
        total['skipped'] += suite['skipped']
        mark = f' {WARN}' if failures else ''
        lines.append(f"| {suite['module']} | {suite['task']} | {suite['tests']:,} | {failures}{mark} | "
                     f"{suite['skipped']} | {suite['time']:,.1f} |")
    lines.append('')
    lines.append(f"합계: 테스트 {total['tests']:,}개, 실패 {total['failures']}, 건너뜀 {total['skipped']}")
    if tests['modules_without_tests']:
        lines.append('테스트가 없는 모듈: ' + ', '.join(code(m) for m in tests['modules_without_tests']))
    limit = 5 if compact else 20
    failed = [f"- {code(f['test_class'])} > {code(f['name'])}: {f['message']}" for f in tests['failed'][:limit]]
    if len(tests['failed']) > limit:
        failed.append(f"- … 외 {len(tests['failed']) - limit}개")
    lines += details(f"{WARN} 실패한 테스트 {len(tests['failed'])}개", failed)
    classes = [(entry['time'], name, suite['module']) for suite in tests['suites']
               for name, entry in suite['classes'].items()]
    slow = [f'- {code(name.rsplit(".", 1)[-1])} ({module}) {seconds:,.1f}초'
            for seconds, name, module in sorted(classes, reverse=True)[:5]]
    if not compact:
        lines += details('오래 걸린 테스트 클래스 5개', slow)
    return lines


def render_contracts(signals):
    rows, notes = [], []

    def failed(signal):
        return 'error' in signal and f"{WARN} 수집 실패: {signal['error']}"

    api = signals['api']
    if failed(api):
        rows.append(('API (OpenAPI 스냅샷)', failed(api)))
    else:
        parts = []
        for group, diff in api.items():
            if diff['added'] or diff['removed'] or diff['changed']:
                parts.append(f"{group} +{len(diff['added'])} −{len(diff['removed'])} ~{len(diff['changed'])}")
                notes += details(f'API {group} operation 변경',
                                 [f'- 추가 {code(x)}' for x in diff['added']]
                                 + [f'- 삭제 {code(x)}' for x in diff['removed']]
                                 + [f'- 변경 {code(x)}' for x in diff['changed']])
        counts = ', '.join(f"{group} {diff['operations']}" for group, diff in api.items())
        rows.append(('API (OpenAPI 스냅샷)', ('; '.join(parts) if parts else '변경 없음') + f' (operation {counts})'))

    migrations = signals['migrations']
    if failed(migrations):
        rows.append(('DB 마이그레이션', failed(migrations)))
    else:
        text = inline([code(n) for n in migrations['added']]) if migrations['added'] else '변경 없음'
        if migrations['violations'] or migrations['problems']:
            text = f"{WARN} {text}"
            notes += details(f'{WARN} 마이그레이션 문제', [f'- {p}' for p in migrations['violations'] + migrations['problems']])
        rows.append(('DB 마이그레이션', text + f" (base 최신 V{migrations['base_latest']})"))

    errors = signals['error_codes']
    if failed(errors):
        rows.append(('오류 코드', failed(errors)))
    else:
        parts = []
        if errors['added']:
            parts.append(f"추가 {len(errors['added'])}")
        if errors['removed']:
            parts.append(f"삭제 {len(errors['removed'])}")
        if errors['changed']:
            parts.append(f"변경 {len(errors['changed'])}")
        if errors['reassigned']:
            parts.append(f"{WARN} 코드 재할당 {len(errors['reassigned'])}")
        rows.append(('오류 코드', (', '.join(parts) if parts else '변경 없음') + f" ({errors['count']}개)"))
        notes += details('오류 코드 변경',
                         [f'- 추가 {code(n)}' for n in errors['added']] + [f'- 삭제 {code(n)}' for n in errors['removed']]
                         + [f"- {code(c['name'])} {c['field']}: {code(c['before'])} → {code(c['after'])}" for c in errors['changed']]
                         + [f'- {WARN} {r}' for r in errors['reassigned']])

    archunit = signals['archunit']
    if failed(archunit):
        rows.append(('ArchUnit 동결', failed(archunit)))
    else:
        parts = [f"{r['label']} {r['before']} → {r['after']}" + (f' {WARN}' if r['added'] else '') for r in archunit['rules']]
        if archunit['stored_rules_changed']:
            parts.append(f'{WARN} 규칙 목록(stored.rules) 변경')
        if archunit['properties_changed']:
            parts.append(f'{WARN} archunit.properties 변경')
        rows.append(('ArchUnit 동결', ' · '.join(parts) or '저장소 없음'))
        notes += details(f'{WARN} 새로 동결된 위반', [f"- {r['label']}: {code(line)}" for r in archunit['rules'] for line in r['added']])

    archive = signals['archive']
    if failed(archive):
        rows.append(('보관 부하 테스트', failed(archive)))
    elif archive['problems']:
        rows.append(('보관 부하 테스트', f"{WARN} 계약 위반 {len(archive['problems'])}건(CI 실패 대상)"))
        notes += details(f'{WARN} 보관 파일 불일치', [f'- {p}' for p in archive['problems']])
    else:
        rows.append(('보관 부하 테스트', '기록된 해시와 일치' + (f" (변경 파일 {len(archive['changed'])}개)" if archive['changed'] else '')))

    config = signals['config']
    if failed(config):
        rows.append(('설정', failed(config)))
    elif config['files']:
        new_required = [f"{code(v)} ({f['path'].rsplit('/', 1)[-1]})" for f in config['files'] for v in f['new_required']]
        text = inline([code(f['path'].rsplit('/', 1)[-1]) for f in config['files']])
        if new_required:
            text += f' · {WARN} 새 필수 환경변수 ' + inline(new_required)
        rows.append(('설정', text))
    else:
        rows.append(('설정', '변경 없음'))

    build = signals['build']
    if failed(build):
        rows.append(('빌드·모듈', failed(build)))
    else:
        parts = [f'모듈 추가 {code(m)}' for m in build['modules_added']] + [f'모듈 삭제 {code(m)}' for m in build['modules_removed']]
        parts += [f"{v['name']} {v['before'] or '-'} → {v['after'] or '-'}" for v in build['versions']]
        rows.append(('빌드·모듈', inline(parts, limit=8) or '변경 없음'))

    return ['| 항목 | 결과 |', '|---|---|'] + [f'| {name} | {value} |' for name, value in rows] + notes


def render_areas(areas_signal, compact):
    if 'error' in areas_signal:
        return [f"{WARN} 영역 분류 실패: {areas_signal['error']}"]
    if not areas_signal:
        return ['바뀐 파일 없음.']
    lines = ['| 영역 | 파일 | 관련 규칙(.claude/rules) |', '|---|---:|---|']
    for area in areas_signal:
        lines.append(f"| {area['area']} | {len(area['files'])} | {', '.join(area['rules']) or '-'} |")
    if not compact:
        files = []
        for area in areas_signal:
            shown = area['files'][:30]
            files += [f"- {code(path)}" for path in shown]
            if len(area['files']) > len(shown):
                files.append(f"- … {area['area']} 외 {len(area['files']) - len(shown)}개")
        lines += details('파일 목록', files)
    return lines


def render(facts, compact=False):
    head = facts.get('pr_head') or facts['head']
    total = len(facts['changed'])
    lines = [MARKER, '## PR 점검 보고서', '',
             f"자동 생성(AI 없음) · head {code(short(head))} · base {code(short(facts['merge_base']))} · 바뀐 파일 {total}개", '',
             '### CI 잡', *render_ci(facts['ci']), '',
             '### 테스트', *render_tests(facts['tests'], facts['ci'], compact), '',
             '### 계약 신호', *render_contracts(facts['contracts']), '',
             '### 바뀐 영역', *render_areas(facts['areas'], compact), '',
             '<sub>보관 부하 테스트 해시와 적용된 마이그레이션 수정은 CI가 실패시키고, 나머지는 표시만 합니다. '
             '생성: scripts/ci/pr_report.py</sub>']
    return '\n'.join(lines) + '\n'


def render_within_limit(facts, limit=COMMENT_LIMIT):
    text = render(facts)
    if len(text) <= limit:
        return text
    text = render(facts, compact=True)
    if len(text) <= limit:
        return text
    note = '\n\n_보고서가 너무 길어 잘랐습니다. 전체 내용은 CI 실행의 Step Summary와 pr-report 아티팩트를 보세요._\n'
    return text[:limit - len(note)] + note


# ---------------------------------------------------------------- CLI

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    tests = commands.add_parser('tests', help='summarize JUnit XML of every module')
    tests.add_argument('--out', required=True)
    facts = commands.add_parser('facts', help='collect facts.json and report.md')
    facts.add_argument('--base', required=True)
    facts.add_argument('--head', default='HEAD')
    facts.add_argument('--tests', help='test-summary.json from the app job (optional)')
    facts.add_argument('--needs-env', help='environment variable holding toJSON(needs)')
    facts.add_argument('--pr', type=int)
    facts.add_argument('--pr-head')
    facts.add_argument('--out-dir', required=True)
    args = parser.parse_args()

    if args.command == 'tests':
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(summarize_tests(), ensure_ascii=False, indent=1) + '\n')
        return

    summary = None
    if args.tests and Path(args.tests).is_file():
        summary = safely(lambda: json.loads(Path(args.tests).read_text()))
    needs = json.loads(os.environ[args.needs_env]) if args.needs_env and os.environ.get(args.needs_env) else None
    collected = collect_facts(args.base, args.head, needs=needs, tests=summary, pr=args.pr, pr_head=args.pr_head)
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / 'facts.json').write_text(json.dumps(collected, ensure_ascii=False, indent=1) + '\n')
    (out / 'report.md').write_text(render_within_limit(collected))
    if args.pr is not None:
        (out / 'pr-number').write_text(f'{args.pr}\n')


if __name__ == '__main__':
    main()
