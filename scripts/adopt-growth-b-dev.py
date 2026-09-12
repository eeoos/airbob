#!/usr/bin/env python3
"""Adopt one preserved FINAL B volume in the ordinary development Compose.

The operator creates Compose resources between explicit stages. This tool never
imports SQL, creates/drops a schema, or creates/removes a MySQL/ES container or
data volume. `prepare` uses the sealed, temporary qualification App/Redis only;
it never starts the usual development application. Cloud operations are absent.
"""
import argparse
import contextlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_contract as contract
import growth_b_search as search
from growth_b_runtime import activated_runtime, qualification_binding, qualify_runtime

_spec = importlib.util.spec_from_file_location('_dev_adoption_restore', ROOT / 'scripts/restore-growth-b-local.py')
restore = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(restore)
require = contract.require
KIND = 'global-b-dev-adoption'
PENDING_SOURCE = 'ACTIVE_VERIFIER_VOLUME_REFERENCED_NOT_YET_QUALIFIED'
QUALIFIED_SOURCE = 'QUALIFIED_SECOND_RUNTIME_SOURCE_RETAINED'
GIB = 1024**3
PASSWORD_ENV = 'AIRBOB_DEV_ADOPTION_MYSQL_PASSWORD'
REDIS_IMAGE = 'sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf'
VOLUME_KEYS = ('Name', 'CreatedAt', 'Driver', 'Options', 'Labels', 'Scope')
STATES = {'baseline': 'ADOPTED_BASELINE_VERIFIED', 'prepare': 'ADOPTED_DATABASE_PREPARED_AND_FROZEN',
          'produce': 'NATIVE_PRODUCER_VERIFIED', 'restore': 'COMPOSE_NATIVE_SEARCH_VERIFIED'}
PREVIOUS = {'baseline': None, 'prepare': 'baseline', 'produce': 'prepare', 'restore': 'produce'}
ROLE = {'baseline': 'mysql', 'prepare': 'mysql', 'produce': 'producer', 'restore': 'service'}


def binding(path):
    path = contract.regular(path).resolve()
    return {'path': str(path), 'sha256': contract.sha(path)}


def bound(value):
    require(isinstance(value, dict) and set(value) == {'path', 'sha256'}
            and Path(value['path']).is_absolute() and contract.digest(value['sha256'])
            and binding(value['path']) == value, 'Reviewed input file changed')
    return contract.read(value['path'])


def sources():
    paths = [Path(__file__), ROOT / 'scripts/restore-growth-b-local.py'] + [ROOT / 'infra/aws/scripts' / name
        for name in ('growth_b_contract.py', 'growth_b_runtime.py', 'growth_b_search.py')]
    return {str(path.relative_to(ROOT)): contract.sha(path) for path in paths}


def safe_path(value):
    require(isinstance(value, str) and value.startswith('/') and not any(char in value for char in '\0\r\n,'),
            'An absolute path without mount separators is required')
    path = Path(value)
    require('..' not in path.parts and all(not item.is_symlink() for item in (path, *path.parents)), 'Symlink input path is forbidden')
    return path


def final_qualification(config):
    proof = bound(config['roundtripReceipt'])
    required = {'migrate-passed', 'etl-and-integrity-passed', 'dump-passed', 'restore-rows-ddl-integrity-passed',
                'http-reads-and-immutable-database-passed', 'small-read-and-runtime-scenarios-passed'}
    require(proof == contract.read(Path(config['releaseDirectory']) / 'qualification.json')
            and proof.get('state') == 'DATASET_DB_AND_HTTP_QUALIFIED' and proof.get('finalScaleSelected') is True
            and proof.get('datasetScale') == contract.FINAL_SCALE and required <= set(proof.get('stages', []))
            and not proof.get('retainedDatabases') and proof.get('timezoneRuntimeQualified') is True,
            'Only the finished sealed FINAL roundtrip qualifies; preservation alone is not qualification')
    return proof


def qualified_retained_source(config, preservation):
    """Bind deliberate retention to the successful continuation, never a failure remnant."""
    require(set(preservation) == {'schemaVersion', 'state', 'sourceRun', 'roundtripReceipt', 'engine',
                'sourceContainerId', 'sourceImageId', 'serverUuid', 'volume', 'privateMysqlDirectory',
                'dataCopied', 'newDatabasesStarted', 'allowedHolder'}
            and preservation['schemaVersion'] == 1 and preservation['state'] == QUALIFIED_SOURCE
            and preservation['roundtripReceipt'] == config['roundtripReceipt']
            and preservation['engine'] == config['engine']
            and Path(preservation['sourceRun']) == Path(config['releaseDirectory']).parent
            and Path(config['roundtripReceipt']['path']) == Path(preservation['sourceRun']) / 'roundtrip.json'
            and preservation['dataCopied'] is False and preservation['newDatabasesStarted'] == 0,
            'Qualified retained source must bind this exact successful final run and engine')
    safe_path(preservation['sourceRun']); safe_path(preservation['privateMysqlDirectory'])
    proof = final_qualification(config)
    target, continuation, lifetime = proof.get('retainedTarget', {}), proof.get('continuation', {}), proof.get('databaseLifetime', {})
    require(set(target) == {'attach', 'serverUuid', 'schema', 'frozen', 'automaticRemoval'}
            and target['schema'] == lifetime.get('finalSoleSchema') == 'airbobdb'
            and target['frozen'] is True and target['automaticRemoval'] is False
            and lifetime.get('mysqlRetainedFrozen') is True
            and lifetime.get('policy') == 'sequential-single-database'
            and lifetime.get('maximumConcurrentDatasetSchemas') == 1 and lifetime.get('plaintextTemporaryDumpBytes') == 0
            and continuation.get('kind') == 'second-repeat-continuation'
            and continuation.get('inheritedFirstRepeat') is True and continuation.get('executedRepeats') == [2]
            and continuation.get('firstRepeatReexecuted') is False and continuation.get('datasetRegenerated') is False
            and continuation.get('localOnly') is True
            and target['attach'] == continuation.get('attach') == lifetime.get('attachedRestore'),
            'Successful continuation must explicitly retain the exact frozen sole airbobdb target')
    attach = bound(target['attach']); plan = bound(continuation['plan'])
    require(plan.get('kind') == 'second-runtime-continuation-plan' and plan.get('output') == preservation['sourceRun']
            and plan.get('sourceRun') == attach.get('sourceRun') and plan['sourceRun'] != preservation['sourceRun']
            and plan.get('firstRepeatInherited') is True and plan.get('executedRepeats') == [2]
            and plan.get('firstRepeatReexecuted') is False and plan.get('datasetRegenerated') is False
            and continuation.get('controller') == plan.get('driver'), 'Continuation plan/source/output provenance differs')
    require(binding(continuation['controller']['path']) == continuation['controller'], 'Continuation controller bytes changed')
    for key, name in (('originalFailureReceipt', 'roundtrip.json'), ('originalScenarioReceipt', 'scenario-qualification.json')):
        reference = continuation[key]
        require(reference['path'] == str(Path(plan['sourceRun']) / name)
                and reference['sha256'] == plan['sourceFiles'][name], 'Inherited continuation source binding differs')
        require(bound(reference).get('state') == 'FAILED', 'Original failed source history must remain explicit')
    require(attach.get('state') == 'SECOND_REPEAT_BASELINE_RESTORED_FROZEN_FINGERPRINT_PENDING'
            and attach.get('schema') == 'airbob_growth_bulk_write_benchmark'
            and attach.get('dumpSha256') == proof['dumpSha256'] == plan['sourceFiles']['release/airbob-growth.sql.gz']
            and attach.get('firstRepeatReexecuted') is False and attach.get('datasetRegenerated') is False
            and attach.get('engine') == preservation['engine']
            and attach.get('containerId') == preservation['sourceContainerId']
            and attach.get('image') == preservation['sourceImageId'] == proof['imageId']
            and attach.get('serverUuid') == preservation['serverUuid'] == target['serverUuid']
            and attach.get('volume') == preservation['volume']
            and attach.get('secretDirectory') == preservation['privateMysqlDirectory'],
            'Retained source identity differs from the exact attached target')
    holder_reference = plan['allowedHolderReceipt']
    allowed_holder = preservation['allowedHolder']
    if holder_reference is None:
        require(allowed_holder is None, 'No unrelated holder was authorized by the continuation plan')
        return None
    require(isinstance(allowed_holder, dict) and set(allowed_holder) == {'receipt', 'labels'}
            and allowed_holder['receipt'] == holder_reference and isinstance(allowed_holder['labels'], dict)
            and all(isinstance(k, str) and isinstance(v, str) for k, v in allowed_holder['labels'].items()),
            'The original holder receipt and observed labels must be explicitly bound')
    holder = bound(holder_reference)
    require(holder.get('state') == PENDING_SOURCE and holder.get('sourceRun') == plan['sourceRun']
            and holder.get('engineId') == config['engine']['engineId'] and holder.get('dockerRootDir') == config['engine']['dockerRootDir']
            and holder.get('holderVolumeReadOnly') is True and holder.get('dataCopied') is False and holder.get('newDatabasesStarted') == 0
            and contract.digest(holder.get('holderContainerId')) and contract.digest(holder.get('sourceContainerId'))
            and re.fullmatch(r'sha256:[0-9a-f]{64}', holder.get('holderImageId', ''))
            and set(holder['volume']) == set(VOLUME_KEYS) and holder['volume']['Driver'] == 'local'
            and not holder['volume']['Options'] and holder['volume']['Scope'] == 'local'
            and holder['volume']['Name'] != preservation['volume']['Name']
            and holder['holderContainerId'] != preservation['sourceContainerId'],
            'The allowed holder must retain its original separate volume and engine identity')
    return {'record': holder, 'labels': allowed_holder['labels']}


def configuration(path, expected_sha):
    require(contract.digest(expected_sha) and contract.sha(path) == expected_sha, 'Explicit reviewed configuration SHA is required')
    config = contract.read(path)
    keys = {'schemaVersion', 'kind', 'datasetId', 'releaseDirectory', 'consumerManifestSha256', 'checksSha256',
            'roundtripReceipt', 'appJar', 'migrationDirectory', 'preservationReceipt', 'engine', 'compose',
            'mysql', 'elasticsearch', 'redisImage', 'javaHome', 'privateAccounts', 'writerPorts', 'timeouts', 'capacity', 'couponPreparation'}
    require(set(config) == keys and config['schemaVersion'] == 1 and config['kind'] == KIND + '-config',
            'Unknown adoption input; inline credentials and external commands are forbidden')
    require(re.fullmatch(contract.DATASET_RE, config['datasetId']), 'Final B dataset ID is required')
    for key in ('releaseDirectory', 'migrationDirectory', 'javaHome', 'privateAccounts'):
        safe_path(config[key])
    for key in ('consumerManifestSha256', 'checksSha256'):
        require(contract.digest(config[key]), 'Missing sealed release SHA')
    for key in ('roundtripReceipt', 'preservationReceipt'):
        bound(config[key])
    require(set(config['appJar']) == {'path', 'sha256'} and binding(config['appJar']['path']) == config['appJar'], 'Pinned application JAR differs')
    engine = config['engine']
    require(set(engine) == {'context', 'configuredEndpoint', 'endpoint', 'engineId', 'dockerRootDir'}
            and all(isinstance(v, str) and v for v in engine.values())
            and all(engine[k].startswith('unix:///') for k in ('configuredEndpoint', 'endpoint')), 'Exact local-unix engine is required')
    compose = config['compose']
    require(set(compose) == {'directory', 'project', 'files', 'network'} and compose['project'] == 'airbob'
            and compose['network'] == 'airbob_local-infra' and isinstance(compose['files'], list) and compose['files'],
            'The ordinary Airbob Compose project/network is required')
    directory = safe_path(compose['directory'])
    require(directory != ROOT and all(safe_path(item['path']).is_relative_to(directory) for item in compose['files'])
            and len({item['path'] for item in compose['files']}) == len(compose['files']), 'Bind the ordinary checkout Compose files explicitly')
    for item in compose['files']:
        require(binding(item['path']) == item, 'Reviewed Compose bytes changed')
    mysql = config['mysql']
    require(set(mysql) == {'container', 'port', 'rootPasswordFile', 'rootPasswordContainerPath'}
            and mysql['container'] == 'mysql' and mysql['port'] == 3307, 'Use the ordinary Compose mysql on port 3307')
    safe_path(mysql['rootPasswordFile'])
    require(re.fullmatch(r'/[A-Za-z0-9_./-]+', mysql['rootPasswordContainerPath'])
            and '..' not in Path(mysql['rootPasswordContainerPath']).parts, 'Unsafe private root mount destination')
    es = config['elasticsearch']
    require(set(es) == {'image', 'producer', 'service', 'repository'} and re.fullmatch(r'sha256:[0-9a-f]{64}', es['image']), 'Pinned native ES image required')
    require(set(es['producer']) == {'container', 'port', 'claim'}
            and re.fullmatch(r'airbob-dev-b-producer-[a-z0-9-]+', es['producer']['container'])
            and re.fullmatch(r'[0-9a-f]{32}', es['producer']['claim'])
            and contract.integer(es['producer']['port'], 1024) and es['producer']['port'] <= 65535
            and es['producer']['port'] not in {3307, 9200}, 'Explicit temporary producer identity is required')
    require(es['service'] == {'container': 'elasticsearch', 'port': 9200}, 'Final ES must be ordinary Compose elasticsearch:9200')
    repository = es['repository']
    require(set(repository) == {'directory', 'containerPath', 'name', 'snapshotRelease'}
            and repository['containerPath'] == '/usr/share/elasticsearch/backup'
            and re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,100}', repository['name'])
            and re.fullmatch(re.escape(config['datasetId']) + r'-search-[a-z0-9][a-z0-9._-]{0,60}', repository['snapshotRelease']),
            'Use the ordinary Compose fs repository path and a new dataset-scoped snapshot')
    safe_path(repository['directory'])
    require(config['redisImage'] == REDIS_IMAGE, 'Qualification Redis must use the reviewed Redis image, not an application image')
    require(config['couponPreparation'] is False, 'Demo coupon creation is not part of adoption')
    require(isinstance(config['writerPorts'], list) and 8080 in config['writerPorts'] and
            all(contract.integer(port, 1024) and port <= 65535 for port in config['writerPorts']), 'Declare the stopped usual app port')
    require(set(config['timeouts']) == {'mysqlOperationSeconds', 'appStartupSeconds', 'searchSourceSeconds', 'snapshotSeconds'}
            and all(contract.integer(v, 60) and v <= 28800 for v in config['timeouts'].values()), 'Bounded phase timeouts required')
    require(set(config['capacity']) == {'incrementalFreeInventoryBytes', 'producerEsBytes', 'serviceEsBytes', 'nativeRepositoryBytes',
            'scratchBytes', 'reserveHostBytes', 'reserveDockerBytes', 'esDiskSafetyBytes'}
            and all(contract.integer(v, 1) for v in config['capacity'].values())
            and all(config['capacity'][k] >= GIB for k in ('reserveHostBytes', 'reserveDockerBytes', 'esDiskSafetyBytes')),
            'Separate incremental capacity budgets and retained reserves are required')
    preservation = bound(config['preservationReceipt'])
    if preservation.get('state') == QUALIFIED_SOURCE:
        qualified_retained_source(config, preservation)
    else:
        require(preservation.get('state') == PENDING_SOURCE
                and Path(preservation['sourceRun']).resolve() == Path(config['releaseDirectory']).resolve().parent
                and preservation.get('dataCopied') is False and preservation.get('newDatabasesStarted') == 0
                and preservation.get('holderVolumeReadOnly') is True
                and preservation['engineId'] == engine['engineId'] and preservation['dockerRootDir'] == engine['dockerRootDir'],
                'Original unqualified preservation record must bind this exact final run/engine')
    volume = preservation['volume']
    require(set(volume) == set(VOLUME_KEYS) and volume['Driver'] == 'local' and not volume['Options'] and volume['Scope'] == 'local'
            and re.fullmatch(r'[a-zA-Z0-9_.-]+', volume['Name'])
            and contract.digest(preservation['sourceContainerId'])
            and re.fullmatch(r'sha256:[0-9a-f]{64}', preservation['sourceImageId']), 'Exact original volume/container metadata required')
    require(Path(mysql['rootPasswordFile']).is_relative_to(directory / '.local/airbob-dev')
            and Path(mysql['rootPasswordFile']).name == 'root-password', 'Root credential must be in the ordinary private dev directory')
    private_accounts = Path(config['privateAccounts'])
    require(private_accounts.is_relative_to(directory / '.local/airbob-dev')
            and private_accounts.name == 'accounts.private.json'
            and private_accounts.parent != Path(mysql['rootPasswordFile']).parent,
            'Individual member credentials need their separate ordinary private dev directory')
    return config


def validate_final(config):
    release = Path(config['releaseDirectory'])
    require(contract.sha(release / 'consumer-manifest.json') == config['consumerManifestSha256']
            and contract.sha(release / 'SHA256SUMS.json') == config['checksSha256'], 'Final release anchors changed')
    proof = final_qualification(config)
    preservation = bound(config['preservationReceipt'])
    require(proof.get('imageId') == preservation['sourceImageId']
            and proof.get('databaseLifetime', {}).get('policy') == 'sequential-single-database'
            and proof['databaseLifetime'].get('maximumConcurrentDatasetSchemas') == 1
            and proof['databaseLifetime'].get('plaintextTemporaryDumpBytes') == 0, 'Original image or one-DB qualification differs')
    validated = contract.validate(release, config['datasetId'], Path(config['migrationDirectory']),
        allow_small=False, expected_app_sha=config['appJar']['sha256'])
    require(validated[0]['finalScaleSelected'] is True and proof['dumpSha256'] == validated[1]['airbob-growth.sql.gz'], 'Final dump identity differs')
    return validated


def volume_identity(value):
    return {key: value.get(key) for key in VOLUME_KEYS}


def container_identity(info):
    """Deliberately exclude Config.Env, command arguments and private contents."""
    return {'containerId': info['Id'], 'container': info['Name'].removeprefix('/'), 'imageId': info['Image'],
            'running': info['State']['Running'], 'labels': info['Config'].get('Labels') or {},
            'mounts': sorted([{key: item.get(key) for key in ('Type', 'Name', 'Source', 'Destination', 'RW')}
                              for item in info['Mounts']], key=lambda item: item['Destination']),
            'networkMode': info['HostConfig']['NetworkMode'], 'memoryBytes': info['HostConfig']['Memory'],
            'networks': {name: {'id': item['NetworkID'], 'aliases': sorted(item.get('Aliases') or [])}
                         for name, item in info['NetworkSettings']['Networks'].items()},
            'portBindings': info['HostConfig'].get('PortBindings') or {}}


def compose_identity(config, info, service):
    labels = info['Config'].get('Labels') or {}
    expected_files = {str(Path(item['path']).resolve()) for item in config['compose']['files']}
    actual_files = set(labels.get('com.docker.compose.project.config_files', '').split(','))
    require(labels.get('com.docker.compose.project') == config['compose']['project']
            and labels.get('com.docker.compose.service') == service
            and labels.get('com.docker.compose.project.working_dir') == config['compose']['directory']
            and actual_files == expected_files, 'Container belongs to another Compose project/service/source')


def port_identity(info, internal, published):
    expected = [{'HostIp': '127.0.0.1', 'HostPort': str(published)}]
    require(info['State']['Running'] is True and info['HostConfig'].get('Privileged') is False
            and info['HostConfig'].get('PortBindings', {}).get(str(internal) + '/tcp') == expected
            and info['NetworkSettings'].get('Ports', {}).get(str(internal) + '/tcp') == expected,
            'Expected running unprivileged loopback service port differs')
    require(not [value for key, value in (info['HostConfig'].get('PortBindings') or {}).items()
                 if key != str(internal) + '/tcp' and value], 'Unexpected extra published service port')


def host_bind_source_matches(actual, expected, config):
    """Allow one exact Docker Desktop host alias after the caller verifies its engine."""
    def canonical_path(value):
        return (isinstance(value, str) and value.startswith('/') and not value.startswith('//')
                and not any(char in value for char in '\0\r\n,')
                and '..' not in Path(value).parts and str(Path(value)) == value)
    if not canonical_path(actual) or not canonical_path(expected): return False
    if actual == expected: return True
    engine = config.get('engine', {}); endpoint = engine.get('endpoint')
    return (sys.platform == 'darwin' and engine.get('context') == 'desktop-linux'
            and isinstance(endpoint, str) and endpoint.startswith('unix:///')
            and '?' not in endpoint and '#' not in endpoint and canonical_path(endpoint[7:])
            and engine.get('configuredEndpoint') == endpoint
            and expected != '/host_mnt' and not expected.startswith('/host_mnt/')
            and actual == '/host_mnt' + expected)


def observe(config, role):
    """Read-only normalized exact claim, before any MySQL connection."""
    require(role in {'mysql', 'producer', 'service'}, 'Unknown observation role')
    require(all(binding(item['path']) == item for item in config['compose']['files']), 'Reviewed Compose source bytes changed')
    require(restore.local_engine_identity(include_configured_endpoint=True) == config['engine'], 'Local Docker engine changed')
    preserved = bound(config['preservationReceipt'])
    ids = restore.existing('container')
    require(preserved['sourceContainerId'] not in ids, 'The original verifier container still exists; adoption is forbidden')
    require(not any(restore.port_open(port) for port in config['writerPorts']), 'The usual application writer must remain stopped')
    mysql = restore.inspect('container', config['mysql']['container'])
    require(mysql['Image'] == preserved['sourceImageId'], 'Compose MySQL must use the exact original 8.4.11 image')
    compose_identity(config, mysql, 'mysql'); port_identity(mysql, 3306, 3307)
    volume = restore.inspect('volume', preserved['volume']['Name'])
    require(volume_identity(volume) == preserved['volume'], 'Preserved volume name/creation/driver/options/labels changed')
    mounts = mysql['Mounts']
    data = [item for item in mounts if item['Type'] == 'volume']
    require(len(data) == 1 and data[0]['Name'] == preserved['volume']['Name']
            and data[0]['Destination'] == '/var/lib/mysql' and data[0]['RW'] is True, 'Compose did not adopt the exact sole original data volume')
    expected_binds = {(str(Path(config['compose']['directory']) / 'docker/mysql/init'), '/docker-entrypoint-initdb.d'),
                      (str(Path(config['mysql']['rootPasswordFile']).parent), str(Path(config['mysql']['rootPasswordContainerPath']).parent))}
    binds = [item for item in mounts if item['Type'] == 'bind']
    require(len(mounts) == 3 and {(item['Source'], item['Destination']) for item in binds} == expected_binds
            and all(item['RW'] is False for item in binds), 'Only the reviewed init and private root read-only binds are allowed')
    values = dict(item.split('=', 1) for item in mysql['Config'].get('Env', []) if '=' in item)
    require(values.get('MYSQL_ROOT_PASSWORD_FILE') == config['mysql']['rootPasswordContainerPath']
            and not values.get('MYSQL_ROOT_PASSWORD'), 'Compose must use the reviewed private root FILE, not an inline password')
    network = restore.inspect('network', config['compose']['network'])
    require(network['Driver'] == 'bridge' and network['Scope'] == 'local'
            and (network.get('Labels') or {}).get('com.docker.compose.project') == 'airbob'
            and (network.get('Labels') or {}).get('com.docker.compose.network') == 'local-infra', 'Ordinary Compose network identity differs')
    selected = [mysql]
    result = {'schemaVersion': 1, 'kind': KIND + '-resource-claim', 'role': role, 'engine': config['engine'],
              'mysql': container_identity(mysql), 'volume': volume_identity(volume), 'sourceContainerAbsent': True,
              'network': {'name': network['Name'], 'id': network['Id'], 'labels': network.get('Labels') or {}}}
    if role != 'mysql':
        spec = config['elasticsearch'][role]
        es = restore.inspect('container', spec['container'])
        require(es['Image'] == config['elasticsearch']['image'], 'Observed ES image differs')
        port_identity(es, 9200, spec['port'])
        if role == 'service':
            compose_identity(config, es, 'elasticsearch')
        else:
            require((es['Config'].get('Labels') or {}).get('airbob.dev-handoff.claim') == spec['claim'], 'Temporary producer ownership claim differs')
        repository = config['elasticsearch']['repository']
        es_data = [item for item in es['Mounts'] if item['Type'] == 'volume']
        es_binds = [item for item in es['Mounts'] if item['Type'] == 'bind']
        require(len(es['Mounts']) == 2 and len(es_data) == len(es_binds) == 1
                and es_data[0]['Destination'] == '/usr/share/elasticsearch/data' and es_data[0]['RW'] is True
                and host_bind_source_matches(es_binds[0]['Source'], repository['directory'], config) and es_binds[0]['Destination'] == repository['containerPath']
                and es_binds[0]['RW'] is (role == 'producer'), 'Native repository/data mounts differ; service repository must be RO')
        es_volume = restore.inspect('volume', es_data[0]['Name'])
        require(es_volume['Driver'] == 'local' and not es_volume.get('Options') and es_data[0]['Name'] != preserved['volume']['Name'], 'ES must use its separate local data volume')
        if role == 'producer':
            require((es_volume.get('Labels') or {}).get('airbob.dev-handoff.claim') == spec['claim'], 'Temporary producer data volume ownership differs')
        else:
            require(es_volume['Name'] == 'airbob_es-data'
                    and (es_volume.get('Labels') or {}).get('com.docker.compose.project') == 'airbob'
                    and (es_volume.get('Labels') or {}).get('com.docker.compose.volume') == 'es-data', 'Final ES must use the ordinary Compose es-data volume')
        result['elasticsearch'] = container_identity(es)
        result['elasticsearchVolume'] = volume_identity(es_volume)
        selected.append(es)
    allowed = {item['Id'] for item in selected}
    retained_holder = qualified_retained_source(config, preserved) if preserved.get('state') == QUALIFIED_SOURCE else {'record': preserved}
    holder_record = retained_holder['record'] if retained_holder else None
    holder_id = holder_record['holderContainerId'] if holder_record else None
    if holder_id is not None and holder_id in ids:
        holder = restore.inspect('container', holder_id)
        require(holder['Id'] == holder_id and holder['Name'] == '/' + holder_record['holderContainerName'] and holder['Image'] == holder_record['holderImageId']
                and holder['State']['Running'] is False and len(holder['Mounts']) == 1
                and holder['Mounts'][0]['Type'] == 'volume' and holder['Mounts'][0]['Name'] == holder_record['volume']['Name']
                and holder['Mounts'][0]['RW'] is False, 'Preservation holder changed or was started')
        if preserved.get('state') == QUALIFIED_SOURCE:
            require((holder['Config'].get('Labels') or {}) == retained_holder['labels']
                    and volume_identity(restore.inspect('volume', holder_record['volume']['Name'])) == holder_record['volume'],
                    'Original holder labels or separate volume metadata changed')
        allowed.add(holder_id)
    require(ids == allowed, 'Unexpected running/stopped container; only the exact MySQL, selected ES and optional stopped holder are allowed')
    for item in selected:
        attached = item['NetworkSettings']['Networks']
        require(set(attached) == {network['Name']} and attached[network['Name']]['NetworkID'] == network['Id']
                and item['HostConfig']['NetworkMode'] == network['Name'], 'Resource is attached to a different network')
    result['holderPresent'] = holder_id in ids
    return result


class FilePasswordDatabase(restore.Database):
    def __init__(self, container, password_path):
        super().__init__(container); self.password_path = password_path

    def command(self, database=True):
        return ['docker', 'exec', '-i', self.container, 'sh', '-c',
                'secret=$1; shift; MYSQL_PWD="$(cat "$secret")" exec mysql -uroot --protocol=TCP -h127.0.0.1 '
                '--default-character-set=utf8mb4 --batch --raw "$@"', 'sh', self.password_path] + ([self.SCHEMA] if database else [])


def private_info(config, info):
    """An in-memory compatibility adapter; never serialize this enriched copy."""
    path = contract.regular(config['mysql']['rootPasswordFile'])
    require(stat.S_IMODE(path.stat().st_mode) == 0o600 and stat.S_IMODE(path.parent.stat().st_mode) == 0o700,
            'Preserved root file/directory must be 0600/0700')
    password = path.read_text().strip()
    require(re.fullmatch(r'[0-9a-f]{64}', password), 'Expected the original generated root credential format')
    original = contract.regular(Path(bound(config['preservationReceipt'])['privateMysqlDirectory']) / 'root-password')
    require(stat.S_IMODE(original.stat().st_mode) == 0o600 and stat.S_IMODE(original.parent.stat().st_mode) == 0o700
            and hmac.compare_digest(password, original.read_text().strip()), 'Private root copy differs from the preserved original')
    return info | {'Config': info['Config'] | {'Env': info['Config']['Env'] + ['MYSQL_ROOT_PASSWORD=' + password]}}


def mysql_connection(config, *, frozen=True):
    info = restore.inspect('container', config['mysql']['container'])
    db = FilePasswordDatabase(info['Id'], config['mysql']['rootPasswordContainerPath'])
    identity = db.identity()
    require(identity['version'] == '8.4.11' and identity['uuid'] == bound(config['preservationReceipt'])['serverUuid'], 'Preserved MySQL UUID/version continuity failed')
    if frozen:
        require(identity['readOnly'] == identity['superReadOnly'] == 1, 'Adopted MySQL must be frozen between stages')
    require({row['Database'] for row in db.rows('SHOW DATABASES')} - restore.SYSTEM_SCHEMAS == {'airbobdb'}, 'Exactly one business schema, airbobdb, is required')
    return private_info(config, info), db


def search_configuration(config, info, *, role=None):
    environment = restore.connection_environment(info)
    value = {'releaseDirectory': config['releaseDirectory'], 'datasetId': config['datasetId'],
             'consumerManifestSha256': config['consumerManifestSha256'], 'checksSha256': config['checksSha256'],
             'migrationDirectory': config['migrationDirectory'], 'appJar': config['appJar']['path'],
             'sourceTimeoutSeconds': config['timeouts']['searchSourceSeconds'],
             'mysql': {'jdbcUrl': environment['AIRBOB_ETL_DB_URL'], 'username': 'root', 'passwordEnvironment': PASSWORD_ENV,
                       'expectedServerUuid': bound(config['preservationReceipt'])['serverUuid']}}
    if role:
        selected = config['elasticsearch'][role]; repository = config['elasticsearch']['repository']
        value.update(snapshotTimeoutSeconds=config['timeouts']['snapshotSeconds'], snapshotRelease=repository['snapshotRelease'],
            elasticsearch={'url': 'http://127.0.0.1:' + str(selected['port']), 'container': selected['container'],
                           'image': config['elasticsearch']['image'], 'version': '8.18.8',
                           'diskSafetyBytes': config['capacity']['esDiskSafetyBytes']},
            repository={'name': repository['name'], 'type': 'fs', 'inventoryRoot': repository['directory'],
                        'settings': {'location': repository['containerPath']}})
    return value, environment['AIRBOB_ETL_DB_PASSWORD']


@contextlib.contextmanager
def password_environment(password):
    previous = os.environ.get(PASSWORD_ENV); os.environ[PASSWORD_ENV] = password
    try:
        yield
    finally:
        if previous is None: os.environ.pop(PASSWORD_ENV, None)
        else: os.environ[PASSWORD_ENV] = previous


def capacity_gate(config, stage, *, host_free, docker_free, allocated_database_bytes):
    budget = config['capacity']
    additional_mysql = budget['incrementalFreeInventoryBytes'] if stage == 'prepare' else 0
    # ES already exists when its stage is observed; budget its prospective index
    # growth, not the already allocated original MySQL a second time.
    additional_es = budget['producerEsBytes'] if stage == 'produce' else budget['serviceEsBytes'] if stage == 'restore' else 0
    native = budget['nativeRepositoryBytes'] if stage == 'produce' else 0
    docker_needed = additional_mysql + additional_es + budget['reserveDockerBytes']
    host_needed = additional_mysql + additional_es + native + budget['scratchBytes'] + budget['reserveHostBytes']
    require(host_free >= host_needed and docker_free >= docker_needed, 'Actual separate host/VM free space is below the incremental stage budget')
    return {'alreadyAllocatedDatabaseBytes': allocated_database_bytes, 'additionalMysqlBudgetBytes': additional_mysql,
            'additionalEsBudgetBytes': additional_es, 'additionalNativeBudgetBytes': native,
            'hostFreeBytes': host_free, 'dockerFreeBytes': docker_free,
            'requiredHostFreeBytes': host_needed, 'requiredDockerFreeBytes': docker_needed, 'reclaimedBytesCredited': 0,
            'hostAndVmFreeAreNeverAdded': True}


def observe_capacity(config, stage, output, db):
    allocated = int(restore.docker('exec', db.container, 'du', '-sk', '/var/lib/mysql', timeout=120).split()[0]) * 1024
    free = int(restore.docker('exec', db.container, 'df', '-P', '-k', '/', timeout=30).splitlines()[-1].split()[3]) * 1024
    return capacity_gate(config, stage, host_free=shutil.disk_usage(output).free, docker_free=free, allocated_database_bytes=allocated)


def previous_receipt(config, stage, reference, config_binding, tool_sources):
    expected = PREVIOUS[stage]
    if expected is None:
        require(reference is None, 'Baseline cannot import an earlier preparation claim')
        return None
    require(reference is not None, 'The exact preceding completed stage receipt is required')
    value = bound(reference)
    require(value.get('kind') == KIND + '-stage' and value.get('stage') == expected and value.get('state') == STATES[expected]
            and value.get('config') == config_binding and value.get('datasetId') == config['datasetId']
            and value.get('productionSources') == tool_sources and value.get('sqlImports') == 0
            and value.get('databaseCreated') is False and value.get('databaseFrozen') is True, 'Previous stage, source, or adoption identity differs')
    for artifact in value['artifacts'].values():
        require(binding(artifact['path']) == artifact, 'Preceding stage evidence changed')
    return value


def prepare_config(config):
    return {'mode': 'local', 'datasetId': config['datasetId'], 'release': config['releaseDirectory'],
            'appJar': config['appJar']['path'], 'privateAccounts': config['privateAccounts'],
            'target': {'container': 'mysql', 'redisImage': config['redisImage']},
            'operationTimeoutSeconds': config['timeouts']['mysqlOperationSeconds'],
            'appStartupTimeoutSeconds': config['timeouts']['appStartupSeconds']}


def account_module(runtime, config):
    tools = Path(runtime) / 'tools'
    expected = contract.read(Path(config['releaseDirectory']) / 'tool-sources.json')
    for path in tools.glob('*.py'):
        require(contract.sha(path) == expected[path.name], 'Sealed preparation source changed')
        if path.stem in sys.modules:
            loaded = getattr(sys.modules[path.stem], '__file__', None)
            require(loaded and Path(loaded).resolve() == path.resolve(), 'A different preparation runtime is already imported')
    sys.path.insert(0, str(tools))
    return __import__('growth_accounts')


def execute_stage(config, stage, output, config_binding, claim_reference, previous_reference, validated):
    tool_sources = sources()
    previous = previous_receipt(config, stage, previous_reference, config_binding, tool_sources)
    claim = bound(claim_reference)
    require(claim.get('config') == config_binding and claim.get('productionSources') == tool_sources,
            'Observed claim belongs to another reviewed configuration or source')
    require({k: v for k, v in claim.items() if k not in {'config', 'productionSources'}} == observe(config, ROLE[stage]),
            'Observed resource claim changed; observe and review again')
    runtime = contract.extract_runtime(Path(config['releaseDirectory']), output / 'runtime')
    runtime_path = output / 'host-runtime-qualification.json'
    qualified = qualify_runtime(Path(config['releaseDirectory']), runtime, runtime_path,
                                expected_checks=validated[1], java_home=config['javaHome'])
    require(os.stat(output).st_dev == os.stat(__import__('tempfile').gettempdir()).st_dev, 'Search scratch and output must share the measured host filesystem')
    artifacts = dict(previous['artifacts']) if previous else {}
    result = {}; db = None; accounts = None
    with activated_runtime(qualified), restore.acquire_fresh_run_lock(runtime):
        try:
            require({k: v for k, v in claim.items() if k not in {'config', 'productionSources'}} == observe(config, ROLE[stage]), 'Resources changed while qualifying runtime')
            info, db = mysql_connection(config)
            capacity = observe_capacity(config, stage, output, db)
            search_config, password = search_configuration(config, info, role=ROLE[stage] if stage in {'produce', 'restore'} else None)
            if stage == 'baseline':
                with password_environment(password):
                    search.capture_baseline(search_config, output / 'baseline')
                artifacts.update(baselineReceipt=binding(output / 'baseline/baseline-receipt.json'),
                                 baselineFingerprint=binding(output / 'baseline/mysql-baseline-fingerprint.json'))
                result['sameOriginalServerUuidAndVolume'] = True
            elif stage == 'prepare':
                # Recheck the entire baseline immediately before any credential
                # mutation. The previous baseline directory remains immutable.
                with password_environment(password):
                    search.capture_baseline(search_config, output / 'baseline-recheck')
                prepared_config = prepare_config(config)
                accounts = account_module(runtime, config)
                credential = Path(config['privateAccounts'])
                if not credential.exists():
                    require(not credential.parent.exists() or credential.parent.is_dir()
                            and stat.S_IMODE(credential.parent.stat().st_mode) == 0o700 and not any(credential.parent.iterdir()),
                            'New business credentials need an absent or empty 0700 directory')
                    accounts.create_private_credentials(contract.read(Path(config['releaseDirectory']) / 'accounts.json'),
                        credential.parent, restore.account_environment(prepared_config))
                private = contract.validate_private_accounts(credential, Path(config['releaseDirectory']),
                    expected_environment=restore.account_environment(prepared_config))
                (output / '.private').mkdir(mode=0o700)
                db.execute('SET GLOBAL super_read_only=OFF; SET GLOBAL read_only=OFF')
                try:
                    result['preparation'] = restore.prepare_service(prepared_config, output, runtime, info, db)
                    prepared = result['preparation']
                    require(prepared.get('passed') is True and prepared.get('readinessVerified') is True
                            and prepared.get('applicationLeftRunning') is False and prepared.get('temporarySessionsRemoved') is True
                            and prepared.get('currentInventory', {}).get('everyHorizonContiguous') is True
                            and prepared.get('accountLogins', {}).get('passed') is True
                            and prepared['accountLogins'].get('crossCredentialRejected') is True
                            and prepared['accountLogins'].get('representativeAccounts') == 3,
                            'Preparation must prove current FREE, representative logins and stopped qualification services')
                finally:
                    try:
                        db.execute('SET GLOBAL super_read_only=ON')
                    finally:
                        accounts.update_login_state(credential, [row['memberId'] for row in private['credentials']],
                            restore.account_environment(prepared_config), usable=False, reason='Qualification app stopped; usual development app is not started by adoption')
                artifacts['preparedFingerprint'] = binding(output / 'prepared-fingerprint.json')
                result.update(privateAccounts=config['privateAccounts'], accountEnvironment=restore.account_environment(prepared_config),
                              privateAccountsUsable=False, usualApplicationStarted=False)
            elif stage == 'produce':
                expected = contract.read(artifacts['preparedFingerprint']['path'])
                before = restore.fingerprint(runtime, Path(config['releaseDirectory']), info, output / 'prepared-recheck.json', config['timeouts']['mysqlOperationSeconds'])
                require(before == expected, 'Prepared database changed before native production')
                with password_environment(password):
                    descriptor = search.produce(search_config, Path(artifacts['baselineReceipt']['path']).parent, output / 'companion', build_index=True)
                search.write(output / 'descriptor.json', descriptor)
                reference = contract.read(output / 'companion/snapshot-reference.json')
                require(reference['elasticsearch']['imageId'] == claim['elasticsearch']['imageId']
                        and contract.read(output / 'companion/mysql-prepared-fingerprint.json') == expected,
                        'Producer native proof differs from the adopted source')
                artifacts.update(descriptor=binding(output / 'descriptor.json'),
                    companionManifest=binding(output / 'companion/manifest.json'),
                    nativeInventory=binding(output / 'companion/native-inventory.json'))
                result.update(producerClusterUuid=reference['elasticsearch']['clusterUuid'], producerSafeForOperatorRemoval=True,
                              producerContainerId=claim['elasticsearch']['containerId'], producerVolume=claim['elasticsearchVolume'],
                              nativeRepositoryRetained=True)
            else:
                require(previous['result']['producerContainerId'] not in restore.existing('container')
                        and previous['result']['producerVolume']['Name'] not in restore.existing('volume'),
                        'Operator must remove only the verified producer container/volume before final Compose ES restoration')
                descriptor = contract.read(artifacts['descriptor']['path'])
                companion = Path(artifacts['companionManifest']['path']).parent
                es = search.Elasticsearch(search_config['elasticsearch'])
                identity = es.identity()
                require(identity['clusterUuid'] != previous['result']['producerClusterUuid'] and es.alias(optional=True) is None,
                        'Final Compose ES must be a separate fresh cluster without an alias')
                with password_environment(password):
                    result['nativeRestore'] = search.restore(search_config, companion, descriptor,
                        Path(artifacts['baselineReceipt']['path']).parent, output / 'native-restore', activate_alias=True)
                require(result['nativeRestore']['state'] == 'SEARCH_RESTORED_AND_ACTIVATED'
                        and result['nativeRestore']['allDocumentSourceFieldsEqual'] is True
                        and result['nativeRestore']['repositoryReadOnly'] is True
                        and result['nativeRestore']['nativeInventoryUnchanged'] is True,
                        'Final native restore did not complete all production equality gates')
                artifacts['nativeRestoreReceipt'] = binding(output / 'native-restore/search-restore-receipt.json')
                after = restore.fingerprint(runtime, Path(config['releaseDirectory']), info,
                    output / 'final-prepared-fingerprint.json', config['timeouts']['mysqlOperationSeconds'])
                require(after == contract.read(artifacts['preparedFingerprint']['path']), 'Adopted DB changed during native handoff')
                require(es.identity() == identity and es.alias() == result['nativeRestore']['activeAlias'],
                        'Final Compose ES identity or alias changed during the last MySQL verification')
                artifacts['finalPreparedFingerprint'] = binding(output / 'final-prepared-fingerprint.json')
                result.update(finalComposeService='elasticsearch', usualApplicationStarted=False, privateAccountsUsable=False)
            require({k: v for k, v in claim.items() if k not in {'config', 'productionSources'}} == observe(config, ROLE[stage]), 'Resource identity changed during stage')
            mysql_connection(config)
            require(sources() == tool_sources and binding(config_binding['path']) == config_binding, 'Reviewed code/config changed during stage')
            stage_receipt = {'schemaVersion': 1, 'kind': KIND + '-stage', 'stage': stage, 'state': STATES[stage],
                'datasetId': config['datasetId'], 'config': config_binding, 'productionSources': tool_sources,
                'preservationReceipt': config['preservationReceipt'], 'resourceClaim': claim_reference, 'previousReceipt': previous_reference,
                'artifacts': artifacts, 'result': result, 'capacity': capacity,
                'hostRuntimeQualification': qualification_binding(runtime_path), 'sqlImports': 0, 'databaseCreated': False,
                'sameVolumeRetained': True, 'databaseFrozen': True, 'maximumConcurrentBusinessDatabases': 1,
                'mysqlServerUuid': bound(config['preservationReceipt'])['serverUuid'],
                'plaintextSqlSpoolBytes': 0, 'cloudExecution': False, 'usualApplicationStarted': False}
            stage_receipt['couponPreparation'] = False
            search.write(output / 'stage-receipt.json', stage_receipt)
            return stage_receipt
        except BaseException as error:
            frozen = False
            if db is not None:
                try:
                    # Never freeze an ID that no longer has the exact observed
                    # engine/container/volume/UUID ownership chain.
                    require({k: v for k, v in claim.items() if k not in {'config', 'productionSources'}} == observe(config, ROLE[stage]), 'Failure target changed')
                    _, current = mysql_connection(config, frozen=False)
                    current.execute('SET GLOBAL super_read_only=ON'); frozen = current.identity()['superReadOnly'] == 1
                except BaseException:
                    pass
            search.write(output / 'failure.json', {'kind': KIND + '-failure', 'stage': stage, 'errorType': type(error).__name__,
                'databaseFrozenAfterFailure': frozen, 'resourcesRetained': True, 'sqlImports': 0, 'cloudExecution': False})
            raise


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['plan', 'observe', *STATES])
    parser.add_argument('--config', type=Path, required=True); parser.add_argument('--config-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--role', choices=['mysql', 'producer', 'service'])
    for name in ('claim', 'previous'):
        parser.add_argument('--' + name, type=Path); parser.add_argument('--' + name + '-sha256')
    args = parser.parse_args(argv)
    config = configuration(args.config, args.config_sha256)
    config_binding = binding(args.config)
    validated = validate_final(config)
    output = safe_path(str(args.output.absolute()))
    require(not output.exists() and output.parent.is_dir() and not output.is_relative_to(Path(config['releaseDirectory'])), 'Use a new output directory outside the sealed release')
    output.mkdir(mode=0o700)
    if args.stage == 'plan':
        require(not args.claim and not args.previous and not args.role, 'Plan accepts only reviewed config')
        value = {'state': 'FINAL_ADOPTION_PLAN_VALIDATED_OFFLINE', 'config': config_binding, 'productionSources': sources(),
                 'stages': list(STATES), 'resourceCreationByOperator': True, 'sqlImports': 0,
                 'usualApplicationStarted': False, 'cloudExecution': False}
        search.write(output / 'plan.json', value)
    elif args.stage == 'observe':
        require(args.role is not None and not args.claim and not args.previous, 'Observe requires one role only')
        value = observe(config, args.role) | {'config': config_binding, 'productionSources': sources()}
        search.write(output / 'claim.json', value)
    else:
        require(args.claim is not None and contract.digest(args.claim_sha256) and args.role is None, 'Execution requires a reviewed resource claim SHA')
        previous = None
        if args.previous:
            require(contract.digest(args.previous_sha256), 'Previous receipt SHA is required')
            previous = {'path': str(args.previous.resolve()), 'sha256': args.previous_sha256}
        require(args.previous or args.previous_sha256 is None, 'Previous receipt path is required')
        value = execute_stage(config, args.stage, output, config_binding,
            {'path': str(args.claim.resolve()), 'sha256': args.claim_sha256}, previous, validated)
    print(json.dumps({'state': value.get('state', 'RESOURCE_CLAIM_OBSERVED'), 'output': str(output), 'sqlImports': 0}))


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        print(json.dumps({'state': 'FAILED', 'errorType': type(error).__name__}), file=sys.stderr)
        sys.exit(1)
