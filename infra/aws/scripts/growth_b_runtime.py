"""Qualify the actual consumer host using authenticated release temporal tools."""
from contextlib import contextmanager
from contextvars import ContextVar
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess

from growth_b_contract import digest, read, require, sha

JVM_INJECTION = {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS',
                 'JDK_JAVAC_OPTIONS', 'CLASSPATH'}
_ACTIVE = ContextVar('airbob_qualified_consumer_runtime', default=None)


def _environment(home, base=None):
    environment = {key: value for key, value in (os.environ if base is None else base).items()
                   if key not in JVM_INJECTION}
    environment['JAVA_HOME'] = str(home)
    environment['PATH'] = str(Path(home) / 'bin') + os.pathsep + environment.get('PATH', '')
    return environment


def _binding(path):
    path = Path(path).resolve()
    require(path.is_file() and os.access(path, os.X_OK), 'The selected JDK tool is unavailable')
    return {'path': str(path), 'sha256': sha(path), 'bytes': path.stat().st_size}


def _load_tool(path, expected_sha256):
    code = path.read_bytes()
    require(hashlib.sha256(code).hexdigest() == expected_sha256, 'Authenticated temporal code changed before import')
    spec = importlib.util.spec_from_file_location('_airbob_authenticated_consumer_timezones', path)
    module = importlib.util.module_from_spec(spec)
    # Execute exactly the authenticated buffer, without a second loader file read.
    exec(compile(code, str(path), 'exec'), module.__dict__)
    return module


def _write(path, report):
    path = Path(path); path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(report, stream, indent=2); stream.write('\n')


def qualify_runtime(release, runtime, output, *, expected_checks, java_home=None):
    """Call only after release validation/extraction; never contact a data service.

    Producer OS/vendor/binary hashes are provenance, not consumer equality gates.
    The consumer independently needs Java 21.0.12+ and the exact selected zones.
    """
    release, runtime = Path(release), Path(runtime)
    report = {'schemaVersion': 1, 'kind': 'airbob-growth-timezone-qualification',
              'state': 'TIMEZONE_RUNTIME_REJECTED', 'passed': False, 'consumerRuntimePassed': False,
              'consumerHost': {'system': platform.system(), 'machine': platform.machine(),
                               'execution': 'current process host', 'remoteExecutionClaimed': False,
                               'producerBinaryEqualityRequired': False}}
    try:
        checks = read(release / 'SHA256SUMS.json')
        names = ('tool-sources.json', 'source-provenance.json', 'timezone-qualification.json')
        require(isinstance(expected_checks, dict) and all(digest(expected_checks.get(name))
                and expected_checks[name] == checks.get(name) == sha(release / name) for name in names),
                'Authenticated runtime input bytes changed')
        expected = read(release / 'tool-sources.json').get('growth_timezones.py')
        tool = runtime / 'tools/growth_timezones.py'
        require(digest(expected) and not tool.is_symlink() and sha(tool) == expected,
                'The sealed temporal qualification tool is missing or changed')
        coordinate = read(release / 'source-provenance.json')['coordinateTimeZones']
        zones = coordinate['resolvedZoneIds']
        require(isinstance(zones, list) and zones and all(isinstance(zone, str) for zone in zones)
                and zones == sorted(set(zones)), 'Actual source coordinate timezone scope is required')
        module = _load_tool(tool, expected)
        observed = module.runtime_qualification(java_home=java_home, selected_zones=zones)
        report.update(observed)
        report['consumerReleaseBindings'] = {'files': {name: checks[name] for name in names},
            'toolPath': str(tool.resolve()), 'growthTimezonesSha256': expected,
            'consumerHelperSha256': sha(Path(__file__).resolve())}
        require(sha(tool) == expected == observed.get('qualificationSourceSha256'),
                'Runtime observation was produced by different sealed tools')
        require(observed.get('state') == 'TIMEZONE_RUNTIME_QUALIFIED'
                and all(observed.get(key) is True for key in
                        ('passed', 'allFixedCasesPassed', 'runtimeIdentityPassed', 'selectedZonesPassed'))
                and observed['scope']['selectedZones'] == zones, 'Consumer timezone semantics are incompatible')
        java = observed['java']
        require(java['javaFeature'] == 21 and re.fullmatch(r'21\.0\.(?:1[2-9]|[2-9][0-9])(?:\..+)?', java['javaVersion']),
                'Consumer requires a supported Java 21.0.12 or newer patch')
        home = Path(java['javaHome']).resolve()
        require(home == Path(java['requestedJavaHome']).resolve(), 'Observed consumer JAVA_HOME differs')
        tools = {name: _binding(home / 'bin' / name) for name in ('java', 'javac')}
        require(tools['java']['path'] == str(Path(java['javaExecutable']).resolve())
                and tools['java']['sha256'] == java['executableSha256'], 'Observed Java executable changed')
        version = subprocess.run([tools['javac']['path'], '--version'], env=_environment(home),
                                 stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=30)
        require(version.returncode == 0 and version.stdout.strip() == 'javac ' + java['javaVersion'],
                'The selected compiler does not match the qualified Java runtime')
        tools['javac']['version'] = version.stdout.strip()
        report['hostJavaTools'] = tools
        report['consumerRuntimePassed'] = True
    except Exception as error:
        report.update(state='TIMEZONE_RUNTIME_REJECTED', passed=False, consumerRuntimePassed=False,
                      consumerErrorType=type(error).__name__)
    _write(output, report)
    if report['consumerRuntimePassed'] is not True:
        raise RuntimeError('Consumer runtime qualification failed; inspect the recorded host runtime JSON')
    return report


def _verify(qualification):
    require(qualification.get('consumerRuntimePassed') is True and qualification.get('passed') is True,
            'A successful consumer host runtime qualification is required')
    binding = qualification['consumerReleaseBindings']
    require(sha(binding['toolPath']) == binding['growthTimezonesSha256'] == qualification['qualificationSourceSha256'],
            'Qualified temporal tool changed before use')
    for name, expected in qualification['hostJavaTools'].items():
        current = _binding(expected['path'])
        require(all(current[key] == expected[key] for key in ('path', 'bytes', 'sha256')),
                'Qualified JDK executable changed before use')
    tzdb = qualification['java']['tzdbFile']
    require(sha(tzdb['path']) == tzdb['sha256'], 'Qualified Java timezone data changed before use')


def qualified_environment(qualification, base=None):
    _verify(qualification)
    environment = _environment(qualification['java']['javaHome'], base)
    for name in ('java', 'javac'):
        found = shutil.which(name, path=environment['PATH'])
        require(found and str(Path(found).resolve()) == qualification['hostJavaTools'][name]['path'],
                'PATH does not resolve to the qualified JDK tools')
    return environment


def active_environment(base):
    """Preserve a caller's existing cleaning policy while binding its JVM paths."""
    qualification = _ACTIVE.get()
    return qualified_environment(qualification, base) if qualification is not None else dict(base)


@contextmanager
def activated_runtime(qualification):
    environment = qualified_environment(qualification)
    keys = JVM_INJECTION | {'PATH', 'JAVA_HOME'}
    previous = {key: os.environ.get(key) for key in keys}
    token = _ACTIVE.set(qualification)
    try:
        for key in keys:
            if key in environment: os.environ[key] = environment[key]
            else: os.environ.pop(key, None)
        yield environment
    finally:
        for key, value in previous.items():
            if value is None: os.environ.pop(key, None)
            else: os.environ[key] = value
        _ACTIVE.reset(token)


def qualification_binding(path):
    path = Path(path).resolve()
    return {'path': str(path), 'sha256': sha(path), 'execution': 'current process host'}
