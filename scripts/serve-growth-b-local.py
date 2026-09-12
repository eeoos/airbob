#!/usr/bin/env python3
"""Supervise a retained local B API; never create a database or an ES index."""
import argparse
import contextlib
import fcntl
import hashlib
import importlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tarfile
import threading
import time
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_contract as contract
import growth_b_runtime as host_runtime
import growth_b_search as search

spec = importlib.util.spec_from_file_location('_growth_b_service_restore', ROOT / 'scripts/restore-growth-b-local.py')
restore = importlib.util.module_from_spec(spec); spec.loader.exec_module(restore)
require, read, sha, write = contract.require, contract.read, contract.sha, restore.write
LABEL = 'airbob.local-service.claim'
IMAGE = 'sha256:156f90b18570dedcefd4345c520f62bbf49065fa337d1fd1d920ac218e543693'
JAR_SHA = 'e0f83dfa40a7359492c1f3bdb9b5fa88902b59504fab59e49781077db5e2285c'
TZDB_SHA = '5906b44e25a8a730131d7ba1beb5d15af27043f6536f1a169ca8a5d8b8bed8f8'
HANDOFFS = ('representative-accounts.private.json', 'demo-accounts.private.json', 'administrator.private.json')
SOURCE_FILES = ('scripts/restore-growth-b-local.py', 'infra/aws/scripts/growth_b_contract.py',
                'infra/aws/scripts/growth_b_runtime.py', 'infra/aws/scripts/growth_b_search.py')


def bound(binding):
    require(isinstance(binding, dict) and set(binding) == {'path', 'sha256'}
            and isinstance(binding['path'], str) and Path(binding['path']).is_absolute()
            and contract.digest(binding['sha256']) and sha(binding['path']) == binding['sha256'], 'Reviewed input binding changed')
    return read(binding['path'])


def private_directory(path):
    path = Path(path)
    require(not path.is_symlink(), 'Private directory cannot be a symlink')
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(path.is_dir() and path.stat().st_mode & 0o777 == 0o700, 'Private directory requires mode 0700')
    return path


def configuration(path, expected_sha):
    config = bound({'path': str(Path(path).resolve()), 'sha256': expected_sha})
    require(set(config) <= {'schemaVersion', 'completionReceipt', 'appImageQualification', 'appContainer', 'redisContainer',
            'redisImage', 'httpPort', 'startupTimeoutSeconds', 'operationTimeoutSeconds', 'monitorIntervalSeconds', 'appMemoryMiB'}
            and config.get('schemaVersion') == 1, 'Unexpected local-service configuration; inline credentials are forbidden')
    require(all(isinstance(config.get(key), str) and re.fullmatch(restore.NAME, config[key]) for key in ('appContainer', 'redisContainer'))
            and config['appContainer'] != config['redisContainer'], 'Distinct local app and Redis names are required')
    require(re.fullmatch(r'sha256:[0-9a-f]{64}', config.get('redisImage', '')), 'Exact local Redis image ID required')
    require(contract.integer(config.get('httpPort'), 1024) and config['httpPort'] <= 65535, 'An unprivileged loopback HTTP port is required')
    for key, default, minimum, maximum in [('startupTimeoutSeconds', 3600, 30, 7200), ('operationTimeoutSeconds', 7200, 60, 28800),
            ('monitorIntervalSeconds', 5, 1, 30), ('appMemoryMiB', 1536, 768, 4096)]:
        require(contract.integer(config.get(key, default), minimum) and config.get(key, default) <= maximum, 'Invalid bounded service setting')
    return config


def read_inputs(config):
    completion = bound(config['completionReceipt'])
    require(completion.get('schemaVersion') == 1 and completion.get('kind') == 'global-b-local-completion'
            and completion.get('state') == 'LOCAL_B_DATABASE_AND_SEARCH_FROZEN'
            and completion.get('cloudExecution') is False and completion.get('finalScaleSelected') is True
            and completion.get('databaseFrozen') is True and completion.get('qualificationApplicationStopped') is True
            and completion.get('maximumConcurrentBusinessDatabases') == 1,
            'A completed local frozen B restore/native receipt is required')
    require(isinstance(completion.get('productionSources'), dict)
            and all(completion['productionSources'].get(name) == sha(ROOT / name) for name in SOURCE_FILES),
            'Production helper source differs from the completed restore/native evidence')
    completion_config = bound(completion['inputConfig'])
    java_home = completion_config['runtime']['javaHome']
    require(isinstance(java_home, str) and Path(java_home).is_absolute() and Path(java_home).is_dir(),
            'Completion must bind the retained host JDK directory')
    engine = completion_config['engine']
    require(set(engine) == {'context', 'configuredEndpoint', 'endpoint', 'engineId', 'dockerRootDir'}
            and all(isinstance(value, str) and value for value in engine.values())
            and all(engine[key].startswith('unix:///') for key in ('configuredEndpoint', 'endpoint')),
            'Completion must identify the exact local Unix Docker engine')
    completion = completion | {'_engine': engine, '_javaHome': java_home}
    restore_config, receipt = bound(completion['restoreConfig']), bound(completion['restoreReceipt'])
    prepared = bound(completion['preparedFingerprint'])
    require(restore_config.get('mode') == 'local' and restore_config.get('operation') == restore.FRESH_OPERATION
            and 'source' not in restore_config and not restore_config.get('allowSmall', False), 'Only the retained final local fresh B dataset is supported')
    require(receipt.get('state') == 'PREPARED_APP_STOPPED_DATABASE_FROZEN' and receipt.get('allRowsAndDdlEqual') is True
            and receipt.get('databaseFrozen') is True and receipt.get('datasetId') == completion['datasetId'] == restore_config['datasetId']
            and receipt.get('operation') == restore.FRESH_OPERATION and receipt.get('preparation', {}).get('passed') is True,
            'Prepared local restore receipt differs')
    require(receipt['environment']['engine'] == {key: value for key, value in engine.items() if key != 'configuredEndpoint'},
            'Completion and fresh restore engine evidence differs')
    db = completion['mysql']; target = restore_config['target']
    for key in ('mysql', 'elasticsearch'):
        item = completion[key]
        require(all(isinstance(item.get(name), str) and re.fullmatch(restore.NAME, item[name])
                    for name in ('container', 'volume', 'networkAlias'))
                and contract.digest(item.get('containerId'))
                and re.fullmatch(r'sha256:[0-9a-f]{64}', item.get('image', ''))
                and re.fullmatch(r'[0-9a-f]{32}', item.get('volumeClaim', '')), 'Invalid retained data resource binding')
    network = completion['network']
    require(re.fullmatch(restore.NAME, network['name']) and contract.digest(network['id'])
            and re.fullmatch(r'[0-9a-f]{32}', network['claim'])
            and db['networkAlias'] == 'mysql' and completion['elasticsearch']['networkAlias'] == 'elasticsearch',
            'Explicit task-owned network and internal dependency aliases required')
    require(all(db[key] == target[target_key] for key, target_key in
                [('container', 'container'), ('volume', 'volume'), ('image', 'image')])
            and db['containerId'] == receipt['targetContainerId'] and db['mysqlServerUuid'] == receipt['mysqlServerUuid']
            and db['port'] == target['mysqlPort'] and completion['network']['name'] == target.get('network'),
            'Completion DB/network differs from the restored target')
    require(completion['privateAccounts'] == restore_config['privateAccounts']
            and Path(completion['serviceEnvironmentFile']).resolve() == Path(receipt['serviceEnvironmentFile']).resolve(),
            'Prepared private input paths differ')
    contract.validate_fingerprint(prepared, require_sealed=False)
    image = bound(config['appImageQualification'])
    require(image.get('state') == 'LOCAL_CANDIDATE_IMAGE_BYTES_VERIFIED' and image.get('imageId') == IMAGE
            and image.get('platform') == {'os': 'linux', 'architecture': 'arm64'}
            and image.get('actualImageFileSha256') == {'/app/app.jar': JAR_SHA, '/opt/java/openjdk/lib/tzdb.dat': TZDB_SHA},
            'Reviewed local app/JAR/TZDB image evidence differs')
    require(sha(restore_config['appJar']) == JAR_SHA, 'Retained application JAR differs from the pinned local image')
    native = bound(completion['elasticsearch']['restoreReceipt'])
    require(native.get('state') == 'SEARCH_RESTORED_AND_ACTIVATED' and native.get('datasetId') == completion['datasetId']
            and native.get('appJarSha256') == JAR_SHA and native.get('allDocumentSourceFieldsEqual') is True
            and native.get('nativeInventoryUnchanged') is True and native.get('repositoryReadOnly') is True
            and native.get('activeAlias') == completion['elasticsearch']['activeAlias']
            and native['mysql']['serverUuid'] == db['mysqlServerUuid'], 'Native ES activation proof differs from the retained local DB')
    return completion, restore_config, receipt, prepared, native


def load_tools(runtime, expected=None):
    directory = Path(runtime) / 'tools'; sys.path.insert(0, str(directory))
    result = {}
    for name in ('growth_accounts', 'growth_inventory', 'growth_settings', 'growth_runtime'):
        path = directory / (name + '.py')
        if expected is not None:
            require(sha(path) == expected.get(name), 'Recorded frozen helper bytes changed')
        module = importlib.import_module(name)
        require(Path(module.__file__).resolve() == (directory / (name + '.py')).resolve(), 'Frozen helper import resolved to another source')
        result[name.removeprefix('growth_')] = module
    return result


def network_member(info, network, alias):
    attached = info.get('NetworkSettings', {}).get('Networks', {}).get(network['name'], {})
    require(attached.get('NetworkID') == network['id'] and alias in (attached.get('Aliases') or []),
            'Container is not on the reviewed network/alias')


def verify_data_container(expected, network, *, mysql=False):
    info = restore.inspect('container', expected['containerId'])
    require(info['Id'] == expected['containerId'] and info['Name'] == '/' + expected['container']
            and info['Image'] == expected['image'] and info['State']['Running'] is True, 'Retained data container identity/state changed')
    network_member(info, network, expected['networkAlias'])
    port = '3306/tcp' if mysql else '9200/tcp'
    published = info.get('NetworkSettings', {}).get('Ports', {}).get(port) or []
    require(published and all(row.get('HostIp') == '127.0.0.1' for row in published), 'Retained data endpoint must be loopback only')
    require({row['HostPort'] for row in published} == {str(expected['port'])}, 'Retained data port binding changed')
    path = '/var/lib/mysql' if mysql else '/usr/share/elasticsearch/data'
    mounts = [row for row in info['Mounts'] if row.get('Destination') == path]
    volume = restore.inspect('volume', expected['volume'])
    require(len(mounts) == 1 and mounts[0].get('Type') == 'volume' and mounts[0].get('Name') == expected['volume']
            and volume['Name'] == expected['volume'] and volume['Driver'] == 'local' and not volume.get('Options')
            and volume.get('Labels', {}).get('airbob.restore.claim') == expected['volumeClaim']
            and volume.get('CreatedAt') == expected['volumeCreatedAt']
            and all((volume.get('Labels') or {}).get(key) == value for key, value in expected['volumeLabels'].items())
            and all((info['Config'].get('Labels') or {}).get(key) == value for key, value in expected['containerLabels'].items()),
            'Retained data volume claim changed')
    users = restore.docker('ps', '-aq', '--no-trunc', '--filter', 'volume=' + expected['volume']).decode().splitlines()
    require(users == [expected['containerId']], 'Retained data volume is shared')
    return info


def verify_single_mysql(expected_id):
    database_ids = set()
    for identifier in restore.docker('ps', '-aq', '--no-trunc').decode().splitlines():
        info = restore.inspect('container', identifier)
        if any(row.get('Destination') == '/var/lib/mysql' for row in info['Mounts']):
            database_ids.add(info['Id'])
    require(database_ids == {expected_id}, 'Another local MySQL data container violates the single-database service boundary')


def es_client(completion):
    expected = completion['elasticsearch']
    url = urllib.parse.urlsplit(expected['url'])
    require(url.scheme == 'http' and url.hostname == '127.0.0.1' and url.port and url.path in ('', '/')
            and not url.username and not url.password and not url.query and not url.fragment, 'ES must use the reviewed loopback endpoint')
    return search.Elasticsearch({'url': expected['url'], 'container': expected['container'], 'image': expected['image'], 'version': '8.18.8'})


def verify_es(completion, native, *, all_documents=False):
    es = es_client(completion); identity = es.identity()
    expected = completion['elasticsearch']
    require(identity == native['elasticsearch'] and identity['clusterUuid'] == expected['clusterUuid']
            and es.alias() == expected['activeAlias'], 'ES cluster or single accommodations write alias changed')
    if all_documents:
        require(es.fingerprint(expected['activeAlias']) == native['fullDocumentFingerprint'], 'Native ES document/mapping fingerprint changed')
    return es


def own_container(resource, claim, network, *, running=None):
    info = restore.inspect('container', resource['id'])
    require(info['Id'] == resource['id'] and info['Name'] == '/' + resource['name'] and info['Image'] == resource['image']
            and (info['Config'].get('Labels') or {}).get(LABEL) == claim
            and not any(row.get('Destination') in {'/var/lib/mysql', '/usr/share/elasticsearch/data'} for row in info['Mounts']),
            'Local service container ownership changed')
    if running is not None:
        require(info['State']['Running'] is running, 'Local service container running state changed')
    require(info.get('HostConfig', {}).get('NetworkMode') == network['name']
            and info['HostConfig'].get('Privileged') is False, 'Local service persistent network/isolation changed')
    attached = info.get('NetworkSettings', {}).get('Networks', {})
    if info['State']['Running']:
        require(set(attached) == {network['name']} and attached[network['name']].get('NetworkID') == network['id']
                and resource['name'] in (attached[network['name']].get('Aliases') or []), 'Local service network changed')
    else:
        # Docker removes live endpoints during a normal stop. Its immutable NetworkMode and
        # exact container claim remain; the current network ID is checked again before start.
        require(set(attached) <= {network['name']} and all(row.get('NetworkID') in (None, '', network['id'])
                for row in attached.values()), 'Stopped local service network changed')
    return info


def container_file_sha(container_id, path, byte_limit):
    """Read one immutable image file through a bounded tar stream; no local JAR copy."""
    process = subprocess.Popen(['docker', 'cp', container_id + ':' + path, '-'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    watchdog = threading.Timer(60, process.kill); watchdog.daemon = True; watchdog.start()
    digest = hashlib.sha256(); found = 0; size = 0
    try:
        with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
            for member in archive:
                require(member.isfile() and Path(member.name).name == Path(path).name and found == 0
                        and 0 <= member.size <= byte_limit, 'Unexpected pinned image file stream')
                found += 1
                with archive.extractfile(member) as source:
                    for data in iter(lambda: source.read(1024 * 1024), b''):
                        size += len(data); require(size <= byte_limit, 'Pinned image file exceeds bound'); digest.update(data)
        require(found == 1 and process.wait(timeout=30) == 0, 'Pinned image file verification failed')
        return digest.hexdigest()
    finally:
        watchdog.cancel()
        if process.poll() is None: process.kill(); process.wait(timeout=30)
        process.stdout.close()


def settings_for_service(base, completion, redis_name):
    """Frozen isolation policy plus the three explicitly identified local dependencies."""
    settings = dict(base)
    settings.update({'reservation.inventory.startup.enabled': 'true', 'management.endpoint.health.probes.enabled': 'true',
        'spring.elasticsearch.uris': 'http://' + completion['elasticsearch']['networkAlias'] + ':9200',
        'spring.data.redis.host': redis_name, 'spring.data.redis.port': '6379',
        'accommodation.detail-cache.redis.host': redis_name, 'accommodation.detail-cache.redis.port': '6379',
        'logging.level.org.hibernate.SQL': 'OFF', 'logging.level.org.hibernate.orm.jdbc.bind': 'OFF',
        'logging.level.org.springframework.web': 'WARN', 'logging.level.org.springframework.security': 'WARN',
        'spring.mvc.log-request-details': 'false', 'server.tomcat.accesslog.enabled': 'false',
        'spring.task.scheduling.enabled': 'false'})
    for key in ('payment.toss.enabled', 'google.api.enabled', 'operator-alert.slack.enabled', 'cloud.aws.s3.write-enabled',
                'accommodation.detail-cache.enabled', 'spring.kafka.listener.auto-startup',
                'accommodation.indexing.kafka.auto-startup', 'accommodation.detail-cache.invalidation.kafka.auto-startup',
                'operator-alert.kafka.auto-startup', 'reservation.inventory.seed.enabled', 'reservation.inventory.retention.enabled'):
        require(str(settings.get(key)).lower() == 'false', 'Frozen external-effect/cache/scheduling isolation differs')
    return settings


def service_secrets(path):
    restore.private_file(path)
    result = {}
    for line in Path(path).read_text().splitlines():
        key, separator, value = line.partition('=')
        require(separator and re.fullmatch(r'[A-Z][A-Z0-9_]*', key) and key not in result
                and value and '\x00' not in value and '\r' not in value, 'Prepared service environment is malformed')
        result[key] = value
    require(result.get('SPRING_DATASOURCE_USERNAME') and result.get('SPRING_DATASOURCE_PASSWORD'), 'Prepared service credentials are missing')
    return result


def mark_accounts(tools, completion, environment, url, *, usable, reason, _already_locked=False):
    lock = contextlib.nullcontext() if _already_locked else account_lock(completion)
    with lock:
        _mark_accounts(tools, completion, environment, url, usable=usable, reason=reason)


def account_lock(completion):
    return file_lock(Path(completion['privateAccounts']).parent / '.local-service-accounts.lock')


def _mark_accounts(tools, completion, environment, url, *, usable, reason):
    accounts = tools['accounts']; path = completion['privateAccounts']
    private = accounts.read_private(path)
    accounts.update_login_state(path, [row['memberId'] for row in private['credentials']], environment, usable=usable, reason=reason)
    for name in HANDOFFS:
        target = Path(path).parent / name
        value = read(target)
        emails = {'representative-accounts.private.json': {'demo@airbob.test', 'host@airbob.test', 'admin@airbob.test'},
                  'demo-accounts.private.json': {'demo@airbob.test', 'host@airbob.test'},
                  'administrator.private.json': {'admin@airbob.test'}}[name]
        require(len(value['credentials']) == len(emails) and {row['email'] for row in value['credentials']} == emails
                and all(row['group'] in {'representative', 'administrator'} for row in value['credentials']),
                'User handoff cannot contain qualification or load accounts')
        value.update(serviceUrl=url, serviceState='VERIFIED' if usable else 'NOT_AVAILABLE', usable=usable,
                     scope='Retained local API service; external integrations and scheduled workers are disabled')
        write(target, value)


def qualify_restart_logins(tools, app, db, bundle, output, environment):
    """Recheck the sealed identity set without comparing mutable user activity to generation counts."""
    accounts = tools['accounts']; selected = accounts.public_account_union(bundle)
    private = accounts.read_private(app.env['AIRBOB_GROWTH_CREDENTIALS_FILE'])
    accounts.representative_rows(bundle['representativeAccounts'])
    app.request(app.client(), '/api/v1/auth/me', expected=(401,), capture=False)
    observed = []
    for item in selected:
        actual = json.loads(db.scalar("SELECT JSON_OBJECT('memberId',id,'email',email,'role',role,'status',status,'nickname',nickname) "
                                      'FROM member WHERE id=' + str(item['memberId'])))
        require(all(actual.get(key) == item[key] for key in ('memberId', 'email', 'role', 'status')),
                'Prepared account identity/active role changed during local use')
        credential = accounts.credential_for_member(private, item['memberId'])
        require(all(credential[key] == actual[key] for key in ('email', 'role')), 'Private credential identity differs')
        client = app.client(); app.login(client, item['email'])
        me = app.request(client, '/api/v1/auth/me', capture=False)['response']['data']
        require(int(me['id']) == actual['memberId'] and me['email'] == actual['email'] and me['nickname'] == actual['nickname'],
                'Normal login returned a different current account identity')
        app.request(client, '/api/v1/auth/logout', method='POST', capture=False)
        app.request(client, '/api/v1/auth/me', expected=(401,), capture=False)
        observed.append({key: actual[key] for key in ('memberId', 'email', 'role', 'status')} | {
            'group': credential['group'], 'normalLoginMeAndLogoutVerified': True})
    first = selected[0]
    app.request(app.client(), '/api/v1/auth/login', method='POST',
                body={'email': first['email'], 'password': 'invalid-' + os.urandom(32).hex()}, expected=(400, 401), capture=False)
    demo = accounts.credential_for_email(private, 'demo@airbob.test')
    targets = [accounts.credential_for_email(private, email) for email in ('host@airbob.test', 'admin@airbob.test')]
    targets += [row for group in ('qualification', 'load')
                if (row := next((item for item in private['credentials'] if item['group'] == group), None))]
    for target in targets:
        app.request(app.client(), '/api/v1/auth/login', method='POST',
                    body={'email': target['email'], 'password': demo['password']}, expected=(400, 401), capture=False)
    proof = {'passed': True, 'scope': 'Retained user-state restart; generation ownership counts are not reapplied',
             'accounts': observed, 'environment': environment, 'identityAndLogoutVerified': True,
             'crossCredentialRejected': True, 'invalidPasswordRejected': True,
             'representatives': {'passed': True, 'identityAndRoleVerified': True,
                 'generationOwnershipCountsRechecked': False,
                 'accounts': [{key: row[key] for key in ('key', 'memberId', 'email', 'role', 'status')}
                              for row in bundle['representativeAccounts']]}, 'credentialAndSessionValuesRecorded': False}
    write(Path(output) / 'restart-account-login-qualification.json', proof)
    return {key: value for key, value in proof.items() if key != 'accounts'} | {'accounts': len(observed)}


class Supervisor:
    def __init__(self, config, output, *, config_path, config_sha):
        self.config, self.output = config, Path(output)
        self.private = private_directory(self.output / '.private')
        self.state_path = self.output / 'service.json'
        self.state = read(self.state_path) if self.state_path.exists() else {
            'schemaVersion': 1, 'kind': 'airbob-retained-local-service', 'state': 'NEW', 'claim': os.urandom(16).hex(),
            'configPath': str(Path(config_path).resolve()), 'configSha256': config_sha, 'installerSha256': sha(Path(__file__)), 'resources': {}}
        require(self.state.get('configSha256') == config_sha and self.state.get('installerSha256') == sha(Path(__file__)), 'Service state/config/code binding changed')
        for role, resource in self.state['resources'].items():
            require(role in {'app', 'redis'} and resource.get('name') == config[role + 'Container']
                    and resource.get('image') == (IMAGE if role == 'app' else config['redisImage'])
                    and contract.digest(resource.get('id')), 'Recorded service container/image binding changed')
        self.url = 'http://127.0.0.1:' + str(config['httpPort'])
        self.stop_requested = False; self.tools = None; self.db = None
        self.startup_baseline_verified = False; self.reached_ready = False; self.qualification = None

    def event(self, state, **values):
        self.state.update(state=state, **values); write(self.state_path, self.state)

    def prepare_inputs(self):
        self.completion, self.restore_config, self.receipt, self.prepared, self.native = read_inputs(self.config)
        self.environment = restore.account_environment(self.restore_config)
        self.dataset_id = self.completion['datasetId']; self.network = self.completion['network']
        self.engine = restore.local_engine_identity(include_configured_endpoint=True)
        require(self.engine == self.completion['_engine'], 'Local Docker engine differs from the retained restore')
        release = Path(self.restore_config['release'])
        require(sha(release / 'consumer-manifest.json') == self.restore_config['consumerManifestSha256']
                and sha(release / 'SHA256SUMS.json') == self.restore_config['checksumsSha256'], 'Retained release anchors changed')
        _, checks, _ = contract.validate(release, self.dataset_id, Path(self.restore_config['backendRoot']) / 'src/main/resources/db/migration',
                                          expected_app_sha=JAR_SHA, verify_gzip=False)
        contract.validate_private_accounts(self.completion['privateAccounts'], release, expected_environment=self.environment)
        self.runtime = restore.extract_runtime(release, self.output / ('runtime-' + os.urandom(6).hex()))
        self.qualification = host_runtime.qualify_runtime(release, self.runtime,
            self.output / ('host-runtime-' + os.urandom(6).hex() + '.json'), expected_checks=checks,
            java_home=self.completion['_javaHome'])
        self.tools = load_tools(self.runtime)
        credentials = contract.validate_private_accounts(self.completion['privateAccounts'], release, expected_environment=self.environment)
        material = {key: credentials[key] for key in ('environment', 'datasetProfile', 'loadPool')}
        material['credentials'] = [{key: row[key] for key in ('memberId', 'email', 'role', 'group', 'password')}
                                   for row in credentials['credentials']]
        credential_sha = hashlib.sha256(json.dumps(material, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        environment_sha = sha(self.completion['serviceEnvironmentFile'])
        require(self.state.get('credentialMaterialSha256', credential_sha) == credential_sha
                and self.state.get('serviceEnvironmentSha256', environment_sha) == environment_sha,
                'Selected credential material changed; no password reset is performed')
        self.event('INPUTS_VERIFIED', datasetId=self.dataset_id, url=self.url, engine=self.engine, network=self.network,
            completionReceipt=self.config['completionReceipt'], privateAccounts=self.completion['privateAccounts'],
            environment=self.environment, runtime=str(self.runtime), credentialMaterialSha256=credential_sha,
            serviceEnvironmentSha256=environment_sha,
            runtimeToolSha256={name: sha(self.runtime / 'tools' / (name + '.py'))
                              for name in ('growth_accounts', 'growth_inventory', 'growth_settings', 'growth_runtime')},
            representativeHandoff=str(Path(self.completion['privateAccounts']).parent / HANDOFFS[0]))

    def verify_dependencies(self, *, frozen):
        require(restore.local_engine_identity(include_configured_endpoint=True) == self.engine, 'Local engine changed')
        info = verify_data_container(self.completion['mysql'], self.network, mysql=True)
        verify_data_container(self.completion['elasticsearch'], self.network)
        network = restore.inspect('network', self.network['name'])
        require(network['Id'] == self.network['id'] and (network.get('Labels') or {}).get('airbob.restore.claim') == self.network['claim'], 'Task-owned network claim changed')
        expected = {self.completion[key]['containerId'] for key in ('mysql', 'elasticsearch')} | {row['id'] for row in self.state['resources'].values()}
        require(set(network.get('Containers') or {}) <= expected, 'An unreviewed container joined the service network')
        self.db = restore.Database(info['Id'])
        identity = self.db.identity()
        require(identity['uuid'] == self.completion['mysql']['mysqlServerUuid'] and identity['version'] == '8.4.11'
                and identity['superReadOnly'] == (1 if frozen else 0), 'Retained MySQL UUID/version/frozen state differs')
        require({row['Database'] for row in self.db.rows('SHOW DATABASES')} - restore.SYSTEM_SCHEMAS == {'airbobdb'}, 'Unexpected business schema')
        require(self.db.scalar("SELECT @@global.time_zone IN ('+00:00','UTC')") == 1, 'MySQL process timezone must be UTC')
        verify_es(self.completion, self.native)
        return info

    def app_client(self):
        app = self.tools['runtime'].App.__new__(self.tools['runtime'].App)
        app.env = {'AIRBOB_GROWTH_CREDENTIALS_FILE': self.completion['privateAccounts']}
        app.base = self.url; app.output = self.output; app.label = 'retained-local-api'
        app.log_path = self.private / 'http-client.log'; app.log_path.touch(mode=0o600)
        return app

    def create_or_reuse(self):
        resources = self.state['resources']
        if resources:
            require(set(resources) == {'app', 'redis'}, 'Incomplete prior service resources require explicit review')
            for resource in resources.values(): own_container(resource, self.state['claim'], self.network, running=False)
            require(not restore.port_open(self.config['httpPort']), 'Local service HTTP port is occupied')
            self.verify_app_files()
            return
        names = set(restore.docker('ps', '-a', '--format', '{{.Names}}').decode().splitlines())
        require(self.config['appContainer'] not in names and self.config['redisContainer'] not in names, 'App or Redis container name already exists')
        require(not restore.port_open(self.config['httpPort']), 'Local service HTTP port is occupied')
        for pin in (IMAGE, self.config['redisImage']):
            image = restore.inspect('image', pin)
            require(image['Id'] == pin and image['Os'] == 'linux' and image['Architecture'] == 'arm64', 'Pinned local service image/platform differs')
        settings = settings_for_service(self.tools['settings'].BASE_SETTINGS, self.completion, self.config['redisContainer'])
        property_path = self.private / 'local-service.properties'
        write_text_private(property_path, '\n'.join(key + '=' + str(value) for key, value in settings.items()) + '\n')
        secrets = service_secrets(self.completion['serviceEnvironmentFile'])
        jdbc = self.tools['settings'].utc_jdbc_url('jdbc:mysql://' + self.completion['mysql']['networkAlias'] + ':3306/airbobdb?allowPublicKeyRetrieval=true&useSSL=false')
        environment = {'SPRING_DATASOURCE_URL': jdbc, 'SPRING_DATASOURCE_USERNAME': secrets['SPRING_DATASOURCE_USERNAME'],
            'SPRING_DATASOURCE_PASSWORD': secrets['SPRING_DATASOURCE_PASSWORD'], 'TZ': 'UTC', 'JAVA_TOOL_OPTIONS': '-Duser.timezone=UTC',
            'AWS_ACCESS_KEY_ID': 'dummy', 'AWS_SECRET_ACCESS_KEY': 'dummy', 'AWS_EC2_METADATA_DISABLED': 'true'}
        env_path = self.private / 'app.env'; write_text_private(env_path, ''.join(key + '=' + value + '\n' for key, value in environment.items()))
        for role, name, image in [('redis', self.config['redisContainer'], self.config['redisImage']), ('app', self.config['appContainer'], IMAGE)]:
            args = ['create', '--name', name, '--network', self.network['name'], '--network-alias', name,
                '--label', LABEL + '=' + self.state['claim'], '--label', 'airbob.dataset.id=' + self.dataset_id,
                '--restart=no', '--log-driver=local', '--log-opt', 'max-size=10m', '--log-opt', 'max-file=2']
            if role == 'redis':
                args += ['--memory=128m', '--tmpfs', '/data:rw,noexec,nosuid,size=64m', image,
                         'redis-server', '--save', '', '--appendonly', 'no']
            else:
                memory = self.config.get('appMemoryMiB', 1536)
                args += ['--memory=' + str(memory) + 'm', '--env-file', str(env_path), '-p', '127.0.0.1:' + str(self.config['httpPort']) + ':8080',
                    '--mount', 'type=bind,source=' + str(property_path) + ',target=/app/local-service.properties,readonly',
                    '--entrypoint', '/opt/java/openjdk/bin/java', image, '-Duser.timezone=UTC', '-Xmx' + str(memory - 384) + 'm',
                    '-jar', '/app/app.jar', '--spring.profiles.active=test', '--server.address=0.0.0.0', '--server.port=8080',
                    '--spring.config.additional-location=file:/app/local-service.properties']
            identifier = restore.docker(*args).decode().strip()
            require(contract.digest(identifier), 'Created service container ID is invalid')
            resources[role] = {'id': identifier, 'name': name, 'image': image}
            self.event('CONTAINERS_CREATED_NOT_READY')
        self.verify_app_files()

    def verify_app_files(self):
        require(container_file_sha(self.state['resources']['app']['id'], '/app/app.jar', 512 * 1024**2) == JAR_SHA
                and container_file_sha(self.state['resources']['app']['id'], '/opt/java/openjdk/lib/tzdb.dat', 4 * 1024**2) == TZDB_SHA,
                'Actual app image JAR or JRE timezone bytes differ')

    def requested_stop(self):
        return self.stop_requested or (self.private / 'stop-requested.json').exists()

    def run(self):
        try:
            self.prepare_inputs()
            with host_runtime.activated_runtime(self.qualification):
                mark_accounts(self.tools, self.completion, self.environment, self.url, usable=False, reason='Local service startup verification is in progress')
                info = self.verify_dependencies(frozen=True)
                verify_single_mysql(self.completion['mysql']['containerId'])
                baseline = bound(self.state['stoppedFingerprint']) if self.state.get('stoppedFingerprint') else self.prepared
                observed = restore.fingerprint(self.runtime, Path(self.restore_config['release']), info,
                    self.output / ('startup-fingerprint-' + os.urandom(6).hex() + '.json'), self.config.get('operationTimeoutSeconds', 7200))
                require(observed == baseline, 'Frozen retained database differs from its reviewed startup checkpoint')
                restarting = bool(self.state.get('stoppedFingerprint'))
                if not restarting: self.tools['inventory'].verify_closed_ownership(self.db)
                owners = self.tools['inventory'].owned_fingerprint(self.db)
                expected_owners = self.state['stoppedOwnerSha256'] if restarting else self.receipt['preparation']['ownerSha256BeforeAndAfter']
                require(owners == expected_owners, 'Retained inventory ownership differs from its startup baseline')
                verify_es(self.completion, self.native, all_documents=True)
                self.startup_baseline_verified = True
                self.create_or_reuse()
                if self.requested_stop(): return
                self.verify_dependencies(frozen=True)
                restore.docker('start', self.state['resources']['redis']['id'])
                self.db.execute('SET GLOBAL super_read_only=OFF; SET GLOBAL read_only=OFF')
                restore.docker('start', self.state['resources']['app']['id'])
                self.event('STARTING_NOT_READY')
                app = self.app_client(); started = time.monotonic(); deadline = started + self.config.get('startupTimeoutSeconds', 3600)
                while True:
                    if self.requested_stop(): return
                    for resource in self.state['resources'].values(): own_container(resource, self.state['claim'], self.network, running=True)
                    try:
                        result = app.request(app.client(), '/actuator/health/readiness', capture=False)
                        if result['response']['status'] == 'UP': break
                    except (OSError, AssertionError): pass
                    require(time.monotonic() < deadline, 'Local inventory/application startup timed out')
                    time.sleep(1)
                self.verify_dependencies(frozen=False)
                inventory = self.tools['inventory'].verify_current_inventory(self.db)
                require(self.tools['inventory'].owned_fingerprint(self.db) == owners, 'Application startup changed historical inventory owners')
                bundle = read(Path(self.restore_config['release']) / 'accounts.json')
                with account_lock(self.completion):
                    logins = qualify_restart_logins(self.tools, app, self.db, bundle, self.output, self.environment) if restarting else (
                        self.tools['accounts'].qualify_account_logins(app, self.db, bundle,
                            self.output, private_input=self.completion['privateAccounts'], environment=self.environment))
                    require(logins['passed'] and logins['crossCredentialRejected'], 'Normal API credential qualification failed')
                    if self.requested_stop(): return
                    mark_accounts(self.tools, self.completion, self.environment, self.url, usable=True,
                                  reason='Actual normal login and readiness passed', _already_locked=True)
                self.reached_ready = True
                self.event('READY', readinessVerified=True, currentInventory=inventory, accountLogins=logins,
                    ownerSha256BeforeAndAfter=owners, startupSeconds=round(time.monotonic() - started, 3),
                    applicationScope='local isolated API; external writes, Kafka, scheduled workers and detail cache disabled',
                    startupBaseline='last successful stopped checkpoint' if restarting else 'sealed prepared restore', usable=True,
                    retainedDatabaseFrozen=False)
                while not self.requested_stop():
                    time.sleep(self.config.get('monitorIntervalSeconds', 5))
                    if self.requested_stop(): break
                    self.verify_dependencies(frozen=False)
                    for resource in self.state['resources'].values(): own_container(resource, self.state['claim'], self.network, running=True)
                    require(app.request(app.client(), '/actuator/health/readiness', capture=False)['response']['status'] == 'UP', 'Local service readiness was lost')
        except BaseException as error:
            if self.requested_stop() and self.startup_baseline_verified and isinstance(error, Exception):
                self.event('STOPPING', usable=False)
            else:
                self.event('FAILED', errorType=type(error).__name__, failedStage=self.state.get('state'), usable=False)
                raise
        finally:
            if self.qualification is not None:
                with host_runtime.activated_runtime(self.qualification): self.shutdown()
            else:
                self.shutdown()

    def shutdown(self):
        failures = []; frozen = False; termination = {}; engine_verified = False
        try:
            if self.tools is not None:
                mark_accounts(self.tools, self.completion, self.environment, self.url, usable=False, reason='Local API service is stopped or unavailable')
        except Exception as error: failures.append(type(error).__name__)
        if self.state['resources'] or self.db is not None:
            try:
                require(restore.local_engine_identity(include_configured_endpoint=True) == self.engine, 'Engine changed before service stop')
                engine_verified = True
            except Exception as error: failures.append(type(error).__name__)
        for role in ('app', 'redis'):
            resource = self.state['resources'].get(role)
            if resource and engine_verified:
                try:
                    info = own_container(resource, self.state['claim'], self.network)
                    if info['State']['Running']: restore.docker('stop', '--time', '30', resource['id'])
                    stopped = own_container(resource, self.state['claim'], self.network, running=False)
                    termination[role] = {key: stopped['State'].get(key) for key in ('ExitCode', 'OOMKilled', 'FinishedAt')}
                except Exception as error: failures.append(type(error).__name__)
        if self.db is not None:
            try:
                require(restore.local_engine_identity(include_configured_endpoint=True) == self.engine, 'Engine changed before freeze')
                info = verify_data_container(self.completion['mysql'], self.network, mysql=True)
                require(self.db.identity()['uuid'] == self.completion['mysql']['mysqlServerUuid'], 'DB identity changed before freeze')
                self.db.execute('SET GLOBAL super_read_only=ON')
                require(self.db.identity()['superReadOnly'] == 1, 'Retained DB freeze failed')
                frozen = True
                # A failed or unverified start must never promote observed drift into a restart baseline.
                if not failures and self.startup_baseline_verified and self.state.get('state') != 'FAILED':
                    path = self.output / ('stopped-fingerprint-' + os.urandom(6).hex() + '.json')
                    restore.fingerprint(self.runtime, Path(self.restore_config['release']), info, path,
                                        self.config.get('operationTimeoutSeconds', 7200))
                    self.state['stoppedFingerprint'] = {'path': str(path), 'sha256': sha(path)}
                    self.state['stoppedOwnerSha256'] = self.tools['inventory'].owned_fingerprint(self.db)
                    self.state['checkpointPreservesUserChanges'] = True
            except Exception as error: failures.append(type(error).__name__)
        self.event('STOPPED' if not failures and frozen and self.state.get('state') != 'FAILED' else 'FAILED',
                   usable=False, retainedDatabaseFrozen=frozen, shutdownErrorTypes=failures, containerTermination=termination)


def write_text_private(path, value):
    require('\x00' not in value, 'Invalid private file content')
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(path, flags, 0o600)
    with os.fdopen(fd, 'w') as stream: stream.write(value)
    Path(path).chmod(0o600)


def state_summary(state):
    return {key: state.get(key) for key in ('state', 'url', 'usable', 'representativeHandoff', 'retainedDatabaseFrozen')}


@contextlib.contextmanager
def file_lock(path, *, nonblocking=False):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | (fcntl.LOCK_NB if nonblocking else 0))
        yield


def supervisor_running(output):
    try:
        with file_lock(Path(output) / '.private/supervisor.lock', nonblocking=True): return False
    except BlockingIOError:
        return True


def recorded_manager(output):
    output = private_directory(Path(output).resolve())
    restore.private_file(output / 'service.json')
    state = read(output / 'service.json')
    config = configuration(state['configPath'], state['configSha256'])
    manager = Supervisor(config, output, config_path=state['configPath'], config_sha=state['configSha256'])
    manager.completion, manager.restore_config, manager.receipt, manager.prepared, manager.native = read_inputs(config)
    manager.environment = restore.account_environment(manager.restore_config)
    manager.engine, manager.network = state['engine'], manager.completion['network']
    require(manager.engine == manager.completion['_engine'] and manager.network == state['network']
            and manager.environment == state['environment'] and manager.completion['privateAccounts'] == state['privateAccounts'],
            'Recorded service dependency/credential environment changed')
    manager.runtime = Path(state['runtime'])
    manager.tools = load_tools(manager.runtime, state['runtimeToolSha256'])
    return manager


def start(config_path, config_sha, output):
    config = configuration(config_path, config_sha); output = private_directory(Path(output).resolve())
    private = private_directory(output / '.private'); state_path = output / 'service.json'; process = None
    with file_lock(private / 'control.lock', nonblocking=True):
        if state_path.exists():
            state = read(state_path)
            require(state['configSha256'] == config_sha and state['installerSha256'] == sha(Path(__file__)), 'Prior service inputs changed')
            if state['state'] == 'READY': return status(output)
            require(state['state'] == 'STOPPED' and not supervisor_running(output),
                    'Prior service is running, starting, or requires review; use status or stop')
            require(state.get('retainedDatabaseFrozen') is True and state.get('stoppedFingerprint')
                    and contract.digest(state.get('stoppedOwnerSha256')), 'A successful frozen stop checkpoint is required for restart')
        (private / 'stop-requested.json').unlink(missing_ok=True)
        manager = Supervisor(config, output, config_path=config_path, config_sha=config_sha)
        manager.event('START_REQUESTED', usable=False)
        fd = os.open(private / 'supervisor.log', os.O_WRONLY | os.O_CREAT | os.O_APPEND | getattr(os, 'O_NOFOLLOW', 0), 0o600)
        with os.fdopen(fd, 'ab') as log:
            process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '_supervise', '--config', str(Path(config_path).resolve()),
                '--config-sha256', config_sha, '--output', str(output)], stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                start_new_session=True, close_fds=True)
    deadline = time.monotonic() + config.get('operationTimeoutSeconds', 7200) + config.get('startupTimeoutSeconds', 3600)
    while time.monotonic() < deadline:
        state = read(state_path)
        if state['state'] == 'READY': return state_summary(state)
        if process.poll() is not None:
            if state['state'] == 'STOPPED': return state_summary(state)
            raise RuntimeError('Local service failed; inspect its private state')
        time.sleep(1)
    write(private / 'stop-requested.json', {'reason': 'Startup deadline exceeded'})
    raise RuntimeError('Local service startup deadline exceeded; graceful stop requested')


def status(output):
    output = private_directory(Path(output).resolve()); restore.private_file(output / 'service.json')
    state = read(output / 'service.json')
    if state['state'] != 'READY': return state_summary(state)
    manager = recorded_manager(output); state = manager.state
    if state['state'] == 'READY':
        try:
            require(supervisor_running(output), 'Local service supervisor is unavailable')
            manager.verify_dependencies(frozen=False)
            for resource in state['resources'].values(): own_container(resource, state['claim'], state['network'], running=True)
            app = manager.app_client()
            require(app.request(app.client(), '/actuator/health/readiness', capture=False)['response']['status'] == 'UP', 'Readiness was lost')
        except Exception:
            # Stop has a dead-supervisor fallback; no stale READY result is exposed.
            return stop(output)
    return state_summary(state)


def stop(output):
    output = private_directory(Path(output).resolve()); state = read(output / 'service.json')
    write(output / '.private/stop-requested.json', {'claim': state['claim']})
    if not state.get('runtime'):
        return {'state': 'STOPPING', 'usable': False, 'serviceStartupCancellationRequested': True}
    manager = recorded_manager(output)
    failures = []
    try:
        mark_accounts(manager.tools, manager.completion, manager.environment, manager.url,
                      usable=False, reason='Operator stopped the local service')
    except Exception as error: failures.append(type(error).__name__)
    require(restore.local_engine_identity(include_configured_endpoint=True) == manager.engine, 'Local service engine changed')
    # Availability and the two exact service containers are stopped before the potentially long checkpoint.
    for role in ('app', 'redis'):
        resource = state['resources'].get(role)
        if resource:
            try:
                info = own_container(resource, state['claim'], state['network'])
                if info['State']['Running']: restore.docker('stop', '--time', '30', resource['id'])
                own_container(resource, state['claim'], state['network'], running=False)
            except Exception as error: failures.append(type(error).__name__)
    deadline = time.monotonic() + manager.config.get('operationTimeoutSeconds', 7200) + 90
    while supervisor_running(output) and time.monotonic() < deadline:
        time.sleep(1)
    if not supervisor_running(output):
        current = read(output / 'service.json')
        if not failures and current.get('state') == 'STOPPED' and current.get('retainedDatabaseFrozen') is True:
            return state_summary(current)
        # A killed supervisor cannot attest a successful user-state checkpoint. Freeze and preserve,
        # but do not silently promote the database into a restartable state or delete any resource.
        try:
            require(restore.local_engine_identity(include_configured_endpoint=True) == manager.engine, 'Engine changed before emergency freeze')
            info = verify_data_container(manager.completion['mysql'], manager.network, mysql=True)
            database = restore.Database(info['Id'])
            require(database.identity()['uuid'] == manager.completion['mysql']['mysqlServerUuid'], 'DB UUID changed before emergency freeze')
            database.execute('SET GLOBAL super_read_only=ON')
            frozen = database.identity()['superReadOnly'] == 1
            require(frozen, 'Retained database freeze failed')
        except Exception as error:
            failures.append(type(error).__name__); frozen = False
        current.update(state='FAILED', usable=False, retainedDatabaseFrozen=frozen,
                       recoveryRequired=True, stopErrorTypes=failures, failure='No successful supervised stop checkpoint')
        write(output / 'service.json', current)
        return state_summary(current)
    return {'state': 'STOPPING', 'url': state.get('url'), 'usable': False, 'databaseAndElasticsearchRetained': True}

def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('start', 'status', 'stop', '_supervise'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--config', type=Path)
    parser.add_argument('--config-sha256')
    args = parser.parse_args()
    if args.command in {'start', '_supervise'}:
        require(args.config is not None and contract.digest(args.config_sha256), 'Reviewed local-service config and SHA required')
    if args.command == 'start': result = start(args.config, args.config_sha256, args.output)
    elif args.command == 'status': result = status(args.output)
    elif args.command == 'stop': result = stop(args.output)
    else:
        output = private_directory(args.output.resolve()); private_directory(output / '.private')
        with file_lock(output / '.private/supervisor.lock', nonblocking=True):
            manager = Supervisor(configuration(args.config, args.config_sha256), output,
                                 config_path=args.config, config_sha=args.config_sha256)
            def stopping(*_): manager.stop_requested = True
            signal.signal(signal.SIGTERM, stopping); signal.signal(signal.SIGINT, stopping)
            manager.event('VALIDATING_INPUTS')
            manager.run()
            result = state_summary(manager.state)
    print(json.dumps(result))


if __name__ == '__main__':
    try: main()
    except BaseException as error:
        print(json.dumps({'state': 'FAILED', 'errorType': type(error).__name__}), file=sys.stderr)
        raise SystemExit(1)
