#!/usr/bin/env python3
"""Complete a qualified FINAL B locally, retaining one frozen MySQL and fresh ES.

Default: authenticate inputs and write an offline plan. --apply additionally uses
the reviewed local-unix Docker engine. It never starts the service application,
publishes images, contacts a cloud, or deletes resources from an earlier run.
Failed runs retain their evidence/resources and attempt to freeze only their DB.
"""
import argparse
import contextlib
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
import threading
import time
import urllib.error
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_contract as contract
import growth_b_search as search
from growth_b_runtime import activated_runtime, qualification_binding, qualify_runtime

_spec = importlib.util.spec_from_file_location('_completion_restore', ROOT / 'scripts/restore-growth-b-local.py')
restore = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(restore)

STATE = 'LOCAL_B_DATABASE_AND_SEARCH_FROZEN'
KIND = 'global-b-local-completion'
ES_IMAGE = 'sha256:f663a26c797b6694ed6f0e76c5fc29927166cf4ad8c4d109ba27d5d254e093ff'
CLAIM = 'airbob.restore.claim'
ROLE = 'airbob.local-completion.role'
PASSWORD_ENV = 'AIRBOB_B_LOCAL_COMPLETION_JDBC_PASSWORD'
GIB = 1024**3
CAPACITY = {'requiredDatabaseBytes', 'requiredProducerEsBytes', 'requiredServiceEsBytes',
            'requiredNativeRepositoryBytes', 'requiredScratchBytes', 'reserveHostBytes',
            'reserveDockerBytes', 'esDiskSafetyBytes'}
FINAL_STAGES = {'migrate-passed', 'etl-and-integrity-passed', 'dump-passed',
                'restore-rows-ddl-integrity-passed', 'http-reads-and-immutable-database-passed',
                'small-read-and-runtime-scenarios-passed'}


def require(value, message):
    contract.require(value, message)


def binding(path):
    path = contract.regular(path).resolve()
    return {'path': str(path), 'sha256': contract.sha(path)}


def bound_input(value):
    require(isinstance(value, dict) and set(value) == {'path', 'sha256'}
            and Path(value['path']).is_absolute() and contract.digest(value['sha256']), 'Exact file binding required')
    require(binding(value['path']) == value, 'Reviewed file bytes or path changed')
    return contract.read(value['path'])


def safe_path(raw, *, exists=True):
    require(isinstance(raw, str) and raw.startswith('/') and not any(c in raw for c in '\n\r\0,'),
            'An absolute path without mount separators is required')
    path = Path(raw)
    require('..' not in path.parts and all(not part.is_symlink() for part in (path, *path.parents)),
            'Symlink paths are forbidden')
    require(not exists or path.exists(), 'Input path is missing')
    return path


def write_new(path, value):
    search.write(path, value)  # O_EXCL, mode 0600; final evidence is never replaced.


def source_bindings():
    paths = [Path(__file__), ROOT / 'scripts/restore-growth-b-local.py',
             *(ROOT / 'infra/aws/scripts' / name for name in
               ('growth_b_contract.py', 'growth_b_runtime.py', 'growth_b_search.py'))]
    return {str(path.relative_to(ROOT)): contract.sha(path) for path in paths}


def configuration(path, expected_sha):
    require(contract.digest(expected_sha) and contract.sha(path) == expected_sha,
            'The reviewed completion configuration SHA is required')
    value = contract.read(path)
    require(set(value) == {'schemaVersion', 'kind', 'datasetId', 'release', 'consumerManifestSha256',
            'checksumsSha256', 'appJar', 'appJarSha256', 'roundtripReceipt', 'toolSourcesSha256',
            'namespace', 'engine', 'images', 'ports', 'runtime', 'capacity', 'timeouts', 'writerPorts'}
            and value['schemaVersion'] == 1 and value['kind'] == KIND + '-config',
            'Unsupported completion configuration; inline credentials and callback commands are forbidden')
    require(re.fullmatch(r'global-growth-b-[0-9a-f]{16}', value['datasetId'])
            and re.fullmatch(r'airbob-b-local-[a-z0-9][a-z0-9-]{0,31}', value['namespace']),
            'Exact B identity and a bounded new local namespace are required')
    for key in ('release', 'appJar'):
        safe_path(value[key])
    for key in ('consumerManifestSha256', 'checksumsSha256', 'appJarSha256', 'toolSourcesSha256'):
        require(contract.digest(value[key]), 'A reviewed input SHA is missing')
    images = value['images']
    require(set(images) == {'mysql', 'redis', 'elasticsearch'} and
            all(re.fullmatch(r'sha256:[0-9a-f]{64}', item) for item in images.values())
            and images['elasticsearch'] == ES_IMAGE, 'Exact local image IDs including the qualified ES image are required')
    engine = value['engine']
    require(set(engine) == {'context', 'configuredEndpoint', 'endpoint', 'engineId', 'dockerRootDir'}
            and all(isinstance(item, str) and item for item in engine.values())
            and all(engine[key].startswith('unix:///') for key in ('endpoint', 'configuredEndpoint'))
            and engine['dockerRootDir'].startswith('/'), 'An explicitly reviewed local-unix engine is required')
    ports = value['ports']
    require(set(ports) == {'mysql', 'producerElasticsearch', 'serviceElasticsearch'}
            and all(contract.integer(p, 1024) and p <= 65535 for p in ports.values())
            and len(set(ports.values())) == 3, 'Three distinct unprivileged loopback ports are required')
    require(isinstance(value['writerPorts'], list) and len(set(value['writerPorts'])) == len(value['writerPorts'])
            and all(contract.integer(p, 1024) and p <= 65535 for p in value['writerPorts']), 'Invalid host writer ports')
    runtime = value['runtime']
    require(set(runtime) == {'javaHome', 'mysqlMemoryMiB', 'mysqlBufferPoolMiB',
                            'elasticsearchMemoryMiB', 'elasticsearchHeapMiB'}, 'Explicit runtime allocations required')
    safe_path(runtime['javaHome'])
    require(all(contract.integer(runtime[key], 128) and runtime[key] <= 32768
                for key in runtime if key != 'javaHome')
            and runtime['mysqlMemoryMiB'] >= 512 and runtime['elasticsearchMemoryMiB'] >= 1024
            and runtime['mysqlBufferPoolMiB'] < runtime['mysqlMemoryMiB']
            and runtime['elasticsearchHeapMiB'] * 2 <= runtime['elasticsearchMemoryMiB'],
            'Bounded MySQL allocation and ES heap no greater than half its allocation are required')
    require(set(value['capacity']) == CAPACITY and all(contract.integer(n, 1) for n in value['capacity'].values())
            and all(value['capacity'][key] >= GIB for key in ('reserveHostBytes', 'reserveDockerBytes', 'esDiskSafetyBytes')),
            'Positive measured-work budgets and explicit disk reserves are required')
    require(set(value['timeouts']) == {'mysqlOperationSeconds', 'appStartupSeconds', 'searchSourceSeconds',
                                     'snapshotSeconds', 'esStartupSeconds'}
            and all(contract.integer(n, 60) and n <= 28800 for n in value['timeouts'].values()),
            'Bounded phase timeouts from 60 to 28800 seconds are required')
    return value


def validate_final(config):
    """Authenticate the real final release; there is deliberately no small override."""
    release = Path(config['release'])
    verify_input_anchors(config)
    roundtrip = bound_input(config['roundtripReceipt'])
    sealed = contract.read(release / 'qualification.json')
    require(roundtrip == sealed and roundtrip.get('state') == 'DATASET_DB_AND_HTTP_QUALIFIED'
            and roundtrip.get('finalScaleSelected') is True and roundtrip.get('datasetScale') == contract.FINAL_SCALE
            and FINAL_STAGES <= set(roundtrip.get('stages', [])) and not roundtrip.get('retainedDatabases')
            and roundtrip.get('imageId') == config['images']['mysql']
            and roundtrip.get('timezoneRuntimeQualified') is True,
            'The completed same-tool FINAL DB/HTTP roundtrip is required; running, small, pilot and retained runs are rejected')
    lifetime = roundtrip.get('databaseLifetime', {})
    require(lifetime.get('policy') == 'sequential-single-database'
            and lifetime.get('maximumConcurrentDatasetSchemas') == 1
            and lifetime.get('plaintextTemporaryDumpBytes') == 0, 'Final qualification must preserve the one-database streaming policy')
    manifest, checks, baseline = contract.validate(release, config['datasetId'], ROOT / 'src/main/resources/db/migration',
                allow_small=False, expected_app_sha=config['appJarSha256'])
    require(manifest['finalScaleSelected'] is True and manifest['datasetScale'] == contract.FINAL_SCALE
            and roundtrip['dumpSha256'] == checks['airbob-growth.sql.gz'], 'Final dataset/dump identity differs')
    tools = contract.read(release / 'tool-sources.json')
    require(contract.digest(tools.get('verify-growth-roundtrip.py')),
            'Authenticated final roundtrip producer source is missing')
    measured = contract.read(release / 'runtime-database-measurement.json')['allocatedTablespaceBytes']
    require(contract.integer(measured, 1) and config['capacity']['requiredDatabaseBytes'] >= measured,
            'The database budget is below the final measured runtime tablespaces')
    return manifest, checks, baseline


def verify_input_anchors(config):
    release = Path(config['release'])
    require(contract.sha(release / 'consumer-manifest.json') == config['consumerManifestSha256']
            and contract.sha(release / 'SHA256SUMS.json') == config['checksumsSha256']
            and contract.sha(config['appJar']) == config['appJarSha256']
            and contract.sha(release / 'tool-sources.json') == config['toolSourcesSha256'], 'Reviewed final input anchors differ')
    require(bound_input(config['roundtripReceipt']) == contract.read(release / 'qualification.json'),
            'The sealed and externally reviewed same-tool roundtrip changed')


def assert_runtime(local, release):
    producer = contract.read(Path(release) / 'timezone-qualification.json')
    require(local.get('consumerRuntimePassed') is True and local['java']['javaVersion'] == '21.0.12.1'
            and all(local['java'][key] == producer['java'][key] for key in ('javaRuntimeVersion', 'javaVendor'))
            and local['java']['tzdbFile']['sha256'] == producer['java']['tzdbFile']['sha256'],
            'Local completion requires the qualified Java 21.0.12.1 and its final-release timezone database')


def planned_names(config):
    prefix = config['namespace']
    return {'network': prefix + '-network', 'observer': prefix + '-disk-observer',
            'mysql': prefix + '-mysql', 'mysqlVolume': prefix + '-mysql-data',
            'producer': prefix + '-producer-es', 'producerVolume': prefix + '-producer-es-data',
            'service': prefix + '-service-es', 'serviceVolume': prefix + '-service-es-data'}


def capacity_gate(capacity, host_free, docker_free, phase):
    """No space from an existing resource is credited; budgets are not measurements."""
    if phase == 'initial':
        es = max(capacity['requiredProducerEsBytes'], capacity['requiredServiceEsBytes'])
        docker_needed = capacity['requiredDatabaseBytes'] + es
        host_needed = docker_needed + capacity['requiredNativeRepositoryBytes'] + capacity['requiredScratchBytes']
    elif phase == 'producer':
        docker_needed = capacity['requiredProducerEsBytes']
        host_needed = docker_needed + capacity['requiredNativeRepositoryBytes'] + capacity['requiredScratchBytes']
    elif phase == 'service':
        docker_needed = capacity['requiredServiceEsBytes']
        host_needed = docker_needed + capacity['requiredScratchBytes']
    else:
        require(phase == 'final', 'Unknown capacity phase')
        host_needed = docker_needed = 0
    require(host_free >= host_needed + capacity['reserveHostBytes']
            and docker_free >= docker_needed + capacity['reserveDockerBytes'], 'Live free space is below the reviewed phase budget and reserve')
    return {'phase': phase, 'observedHostFreeBytes': host_free, 'observedDockerFreeBytes': docker_free,
            'budgetedAdditionalHostBytes': host_needed, 'budgetedAdditionalDockerBytes': docker_needed,
            'reserveHostBytes': capacity['reserveHostBytes'], 'reserveDockerBytes': capacity['reserveDockerBytes'],
            'reclaimableBytesCredited': 0}


def loopback_port(info, internal_port, expected_port):
    published = info['NetworkSettings'].get('Ports', {}).get(str(internal_port) + '/tcp') or []
    bindings = info['HostConfig'].get('PortBindings', {}).get(str(internal_port) + '/tcp') or []
    expected = [{'HostIp': '127.0.0.1', 'HostPort': str(expected_port)}]
    require(bindings == expected and (published == expected if info['State']['Running'] else published in ([], expected)),
            'The exact service port must be bound exclusively to IPv4 loopback')
    require(not {key for key, entries in info['NetworkSettings'].get('Ports', {}).items()
                 if entries and key != str(internal_port) + '/tcp'}, 'Unexpected additional published service port')


def verify_resource(resource, info, volume, network, native=None):
    require(info['Id'] == resource['containerId'] and info['Name'] == '/' + resource['container']
            and info['Image'] == resource['image'], 'Owned container ID/name/image changed')
    labels = info['Config'].get('Labels') or {}
    require(all(labels.get(key) == value for key, value in resource['containerLabels'].items()), 'Container ownership claim changed')
    require(volume['Name'] == resource['volume'] and volume['Driver'] == 'local' and not volume.get('Options')
            and volume['CreatedAt'] == resource['volumeCreatedAt']
            and all((volume.get('Labels') or {}).get(key) == value for key, value in resource['volumeLabels'].items()),
            'Owned volume identity/claim changed')
    mounts = info['Mounts']
    data = [entry for entry in mounts if entry['Type'] == 'volume']
    require(len(data) == 1 and data[0]['Name'] == resource['volume']
            and data[0]['Destination'] == resource['dataPath'] and data[0].get('RW') is True,
            'The container must have exactly its sole owned data volume')
    if native is None:
        require(len(mounts) == 1, 'Unexpected MySQL mount')
    else:
        binds = [entry for entry in mounts if entry['Type'] == 'bind']
        require(len(mounts) == 2 and len(binds) == 1 and binds[0]['Source'] == str(native)
                and binds[0]['Destination'] == '/backup' and binds[0]['RW'] is (resource['role'] == 'producer'),
                'The exact native repository must be writable only in the producer')
    attached = info['NetworkSettings']['Networks']
    require(info['HostConfig'].get('Privileged') is False
            and info['HostConfig']['NetworkMode'] == network['name']
            and info['HostConfig']['Memory'] == resource['memoryMiB'] * 1024**2,
            'Owned network, alias or memory allocation changed')
    if info['State']['Running']:
        require(set(attached) == {network['name']} and attached[network['name']]['NetworkID'] == network['id']
                and resource['networkAlias'] in (attached[network['name']].get('Aliases') or []),
                'The running resource is not attached to the exact owned network and alias')
    else:
        # Stop tears down live endpoint/port/DNS state. Persistent NetworkMode,
        # PortBindings, ID, image, claims and mounts must still match exactly.
        require(set(attached) <= {network['name']} and all(entry.get('NetworkID') in (None, '', network['id'])
                for entry in attached.values()), 'Stopped resource retains an unexpected network')
    loopback_port(info, resource['internalPort'], resource['port'])


class Resources:
    """Only producer ES and this run's volume-free observer can be removed."""
    def __init__(self, config, output):
        self.config, self.output = config, output
        self.names = planned_names(config)
        self.claim = uuid.uuid4().hex
        self.network = None
        self.observer = None
        self.mysql = None
        self.es = None
        self.old_volumes = set()
        self.old_networks = {}
        self.events = []

    def event(self, action, **values):
        self.events.append({'action': action, 'at': dt.datetime.now(dt.timezone.utc).isoformat(), **values})
        restore.write(self.output / 'resource-lifetime.json', {'schemaVersion': 1, 'events': self.events,
            'maximumConcurrentBusinessDatabases': 1, 'policy': 'retain MySQL; sequential producer and service ES'})

    def engine(self):
        require(restore.local_engine_identity(include_configured_endpoint=True) == self.config['engine'],
                'Reviewed local Docker engine changed')

    def empty(self):
        self.engine()
        require(not restore.existing('container'), 'Every business DB and other running/stopped container must be absent before local completion')
        self.old_volumes = restore.existing('volume')
        require(not self.old_volumes & {self.names[key] for key in ('mysqlVolume', 'producerVolume', 'serviceVolume')},
                'A target data volume already exists')
        self.old_networks = {row['ID']: row['Name'] for row in
            (json.loads(line) for line in restore.docker('network', 'ls', '--no-trunc', '--format', '{{json .}}').decode().splitlines())}
        require(self.names['network'] not in self.old_networks.values(), 'The task network must be new')
        require(not any(restore.port_open(port) for port in [*self.config['ports'].values(), *self.config['writerPorts']]),
                'A requested service or declared host writer port is already in use')
        images = {}
        for role, image in self.config['images'].items():
            info = restore.inspect('image', image)
            require(info['Id'] == image and info['Os'] == 'linux' and info['Architecture'] == 'arm64',
                    'The reviewed local arm64 image is absent or different; this tool never pulls images')
            images[role] = {'id': info['Id'], 'os': info['Os'], 'architecture': info['Architecture']}
        engine = json.loads(restore.docker('info', '--format', '{{json .}}'))
        allocation = self.config['runtime']
        require((allocation['mysqlMemoryMiB'] + allocation['elasticsearchMemoryMiB'] + 512) * 1024**2 <= engine['MemTotal'],
                'Reviewed DB/ES allocations leave insufficient Docker VM memory')
        return {'engine': self.config['engine'], 'preExistingContainerIds': [],
                'preExistingVolumeNames': sorted(self.old_volumes), 'preExistingNetworks': self.old_networks,
                'images': images, 'dockerVmMemoryBytes': engine['MemTotal'], 'dockerVmCpuCount': engine['NCPU']}

    def start_network_observer(self):
        identifier = restore.docker('network', 'create', '--driver', 'bridge', '--label', CLAIM + '=' + self.claim,
                    '--label', 'airbob.dataset.id=' + self.config['datasetId'], self.names['network']).decode().strip()
        self.network = {'name': self.names['network'], 'id': identifier, 'claim': self.claim}
        self.verify_network(set())
        identifier = restore.docker('run', '-d', '--pull=never', '--name', self.names['observer'],
            '--read-only', '--network', 'none', '--memory=32m', '--tmpfs', '/data:rw,noexec,nosuid,size=1m',
            '--entrypoint', '/bin/sh', '--label', 'airbob.restore.temporary=true',
            '--label', 'airbob.restore.observer=' + restore.FRESH_OPERATION,
            '--label', 'airbob.restore.claim=' + self.claim,
            self.config['images']['redis'], *restore.OBSERVER_COMMAND).decode().strip()
        self.observer = {'name': self.names['observer'], 'id': identifier}
        self.verify_observer()
        self.event('NETWORK_AND_OBSERVER_CREATED', network=self.network, observer=self.observer)

    def verify_network(self, identifiers):
        self.engine()
        info = restore.inspect('network', self.network['id'])
        require(info['Id'] == self.network['id'] and info['Name'] == self.network['name']
                and info['Driver'] == 'bridge' and info['Scope'] == 'local'
                and (info.get('Labels') or {}).get(CLAIM) == self.claim
                and (info.get('Labels') or {}).get('airbob.dataset.id') == self.config['datasetId']
                and set(info.get('Containers') or {}) == identifiers, 'Owned network identity/membership changed')

    def verify_observer(self):
        info = restore.inspect('container', self.observer['id'])
        labels = info['Config'].get('Labels') or {}
        require(info['Id'] == self.observer['id'] and info['Name'] == '/' + self.observer['name']
                and info['Image'] == self.config['images']['redis'] and info['State']['Running'] is True
                and info['HostConfig']['NetworkMode'] == 'none' and info['HostConfig']['ReadonlyRootfs'] is True
                and all(entry['Type'] == 'tmpfs' for entry in info['Mounts'])
                and info['Config']['Entrypoint'] == ['/bin/sh'] and info['Config']['Cmd'] == restore.OBSERVER_COMMAND
                and labels.get('airbob.restore.claim') == self.claim
                and labels.get('airbob.restore.observer') == restore.FRESH_OPERATION
                and labels.get('airbob.restore.temporary') == 'true', 'Observer identity or volume-free isolation changed')

    def adopt_mysql(self, receipt):
        require(receipt['state'] == 'PREPARED_APP_STOPPED_DATABASE_FROZEN' and receipt['databaseFrozen'] is True
                and receipt['preparation']['passed'] is True and receipt['allRowsAndDdlEqual'] is True,
                'Fresh restore did not leave a verified frozen prepared DB')
        info = restore.inspect('container', receipt['targetContainerId'])
        volume = restore.inspect('volume', self.names['mysqlVolume'])
        claim = (volume.get('Labels') or {}).get('airbob.restore.claim', '')
        require(re.fullmatch(r'[0-9a-f]{32}', claim), 'Prepared MySQL has no exact volume claim')
        self.mysql = {'container': self.names['mysql'], 'containerId': receipt['targetContainerId'],
            'image': self.config['images']['mysql'], 'volume': self.names['mysqlVolume'], 'volumeClaim': claim,
            'volumeCreatedAt': volume['CreatedAt'], 'mysqlServerUuid': receipt['mysqlServerUuid'],
            'networkAlias': 'mysql', 'role': 'mysql', 'dataPath': '/var/lib/mysql', 'internalPort': 3306,
            'port': self.config['ports']['mysql'], 'memoryMiB': self.config['runtime']['mysqlMemoryMiB'],
            'containerLabels': {'airbob.dataset.id': self.config['datasetId']},
            'volumeLabels': {'airbob.dataset.id': self.config['datasetId'], 'airbob.restore.claim': claim}}
        self.verify_mysql()
        self.exclusive()
        self.event('PREPARED_MYSQL_RETAINED', mysql=self.mysql)

    def verify_mysql(self):
        info = restore.inspect('container', self.mysql['containerId'])
        verify_resource(self.mysql, info, restore.inspect('volume', self.mysql['volume']), self.network)
        require(info['State']['Running'] is True, 'Prepared MySQL must remain running')
        db = restore.Database(self.mysql['containerId'])
        identity = db.identity()
        require(identity == {'version': '8.4.11', 'uuid': self.mysql['mysqlServerUuid'], 'readOnly': 1, 'superReadOnly': 1},
                'Prepared MySQL UUID/version/freeze changed')
        require({row['Database'] for row in db.rows('SHOW DATABASES')} - restore.SYSTEM_SCHEMAS == {'airbobdb'},
                'The sole MySQL has an unexpected business schema')
        return info, db

    def exclusive(self):
        self.engine()
        attached = {item['containerId'] for item in (self.mysql, self.es) if item}
        allowed = attached | ({self.observer['id']} if self.observer else set())
        require(restore.existing('container') == allowed, 'An unowned running/stopped container appeared')
        expected_volumes = self.old_volumes | {item['volume'] for item in (self.mysql, self.es) if item}
        require(restore.existing('volume') == expected_volumes, 'The exact volume inventory changed')
        self.verify_network(attached)

    def remove_observer(self):
        self.exclusive(); self.verify_observer()
        identifier = self.observer['id']
        restore.docker('stop', '--time', '10', identifier, timeout=30)
        info = restore.inspect('container', identifier)
        require(info['State']['Running'] is False and info['Name'] == '/' + self.observer['name']
                and all(item['Type'] == 'tmpfs' for item in info['Mounts']), 'Observer changed before removal')
        restore.docker('rm', identifier)
        require(identifier not in restore.existing('container'), 'Observer removal is incomplete')
        self.event('OBSERVER_REMOVED', containerId=identifier)
        self.observer = None
        self.exclusive()

    def start_es(self, role, native):
        require(role in {'producer', 'service'} and self.es is None, 'Only one task ES cluster may exist at a time')
        self.exclusive(); self.verify_mysql()
        volume_name = self.names[role + 'Volume']
        require(volume_name not in restore.existing('volume'), 'The new ES volume is not empty/absent')
        labels = {CLAIM: self.claim, ROLE: role, 'airbob.dataset.id': self.config['datasetId']}
        label_args = [arg for key, value in labels.items() for arg in ('--label', key + '=' + value)]
        restore.docker('volume', 'create', *label_args, volume_name)
        volume = restore.inspect('volume', volume_name)
        require(volume['Driver'] == 'local' and not volume.get('Options')
                and all((volume.get('Labels') or {}).get(k) == v for k, v in labels.items()), 'New ES volume was concurrently claimed')
        self.event('ES_VOLUME_CLAIMED', role=role, volume=volume_name, volumeClaim=self.claim,
                   volumeCreatedAt=volume['CreatedAt'])
        port = self.config['ports']['producerElasticsearch' if role == 'producer' else 'serviceElasticsearch']
        require(not restore.port_open(port), 'The new ES loopback port is occupied')
        allocation = self.config['runtime']
        mount = 'type=bind,source=' + str(native) + ',target=/backup' + (',readonly' if role == 'service' else '')
        alias = 'producer-elasticsearch' if role == 'producer' else 'elasticsearch'
        identifier = restore.docker('create', '--pull=never', '--name', self.names[role], *label_args,
            '--memory', str(allocation['elasticsearchMemoryMiB']) + 'm',
            '--network', self.network['name'], '--network-alias', alias,
            '-e', 'ES_JAVA_OPTS=-Xms' + str(allocation['elasticsearchHeapMiB']) + 'm -Xmx' + str(allocation['elasticsearchHeapMiB']) + 'm',
            '-e', 'discovery.type=single-node', '-e', 'xpack.security.enabled=false', '-e', 'path.repo=/backup',
            '-p', '127.0.0.1:' + str(port) + ':9200', '--mount', mount,
            '--mount', 'type=volume,source=' + volume_name + ',target=/usr/share/elasticsearch/data',
            self.config['images']['elasticsearch']).decode().strip()
        self.es = {'container': self.names[role], 'containerId': identifier, 'image': self.config['images']['elasticsearch'],
            'volume': volume_name, 'volumeClaim': self.claim, 'volumeCreatedAt': volume['CreatedAt'],
            'containerLabels': labels, 'volumeLabels': labels, 'dataPath': '/usr/share/elasticsearch/data',
            'networkAlias': alias, 'internalPort': 9200, 'port': port, 'role': role,
            'memoryMiB': allocation['elasticsearchMemoryMiB'], 'url': 'http://127.0.0.1:' + str(port)}
        # Docker has no published runtime port until start; retain a failed create.
        created = restore.inspect('container', identifier)
        require(created['Id'] == identifier and created['Image'] == self.es['image']
                and created['Name'] == '/' + self.es['container'], 'Created ES identity differs')
        self.event('ES_CONTAINER_CREATED_NOT_STARTED', elasticsearch=self.es)
        restore.docker('start', identifier)
        verify_resource(self.es, restore.inspect('container', identifier), volume, self.network, native)
        self.exclusive()
        config = {'url': self.es['url'], 'container': self.es['container'], 'image': self.es['image'],
                  'version': '8.18.8', 'diskSafetyBytes': self.config['capacity']['esDiskSafetyBytes']}
        es = search.Elasticsearch(config)
        deadline = time.monotonic() + self.config['timeouts']['esStartupSeconds']
        while True:
            try:
                identity = es.identity()
                require(identity['clusterUuid'] not in {'', '_na_'}, 'The new ES cluster UUID is not ready')
                break
            except (urllib.error.URLError, ConnectionError, TimeoutError):
                require(time.monotonic() < deadline, 'New ES startup timed out')
                time.sleep(1)
        require(es.alias(optional=True) is None, 'A fresh ES cluster already has a service alias')
        self.es['clusterUuid'] = identity['clusterUuid']
        self.event('ES_CREATED', elasticsearch=self.es, observedRuntime=identity)
        return config, es, identity

    def remove_producer(self, native, seal):
        require(self.es is not None and self.es['role'] == 'producer', 'Service ES deletion is forbidden')
        require(verify_producer_seal(seal)['producer'] == self.es, 'The removal seal belongs to another producer resource')
        self.exclusive(); self.verify_mysql()
        resource = self.es
        def owned():
            verify_resource(resource, restore.inspect('container', resource['containerId']),
                            restore.inspect('volume', resource['volume']), self.network, native)
            users = {identifier for identifier in restore.existing('container') if any(
                item.get('Type') == 'volume' and item.get('Name') == resource['volume']
                for item in restore.inspect('container', identifier)['Mounts'])}
            require(users == {resource['containerId']}, 'The producer data volume has another user')
        owned()
        restore.docker('stop', '--time', '30', resource['containerId'], timeout=60)
        owned()
        require(restore.inspect('container', resource['containerId'])['State']['Running'] is False,
                'The producer did not stop; its data is retained')
        restore.docker('rm', resource['containerId'])
        require(resource['containerId'] not in restore.existing('container'), 'Producer container is still present')
        volume = restore.inspect('volume', resource['volume'])
        require(volume['CreatedAt'] == resource['volumeCreatedAt'] and (volume.get('Labels') or {}).get(CLAIM) == self.claim,
                'Producer volume changed after container removal')
        require(not any(item.get('Type') == 'volume' and item.get('Name') == resource['volume']
                        for identifier in restore.existing('container')
                        for item in restore.inspect('container', identifier)['Mounts']), 'Producer volume gained another user')
        restore.docker('volume', 'rm', resource['volume'])
        require(resource['volume'] not in restore.existing('volume'), 'Producer data volume removal is incomplete')
        self.es = None
        self.event('SEALED_PRODUCER_ONLY_REMOVED', containerId=resource['containerId'], volume=resource['volume'],
                   producerSeal=seal, nativeRepositoryRetained=str(native))
        self.exclusive()
        return resource


def fresh_config(config, resources, output):
    names, allocation = resources.names, config['runtime']
    return {'schemaVersion': 1, 'mode': 'local', 'operation': restore.FRESH_OPERATION,
        'datasetId': config['datasetId'], 'release': config['release'],
        'consumerManifestSha256': config['consumerManifestSha256'], 'checksumsSha256': config['checksumsSha256'],
        'backendRoot': str(ROOT), 'appJar': config['appJar'], 'allowSmall': False,
        'privateAccounts': str(output / 'account-credentials/accounts.private.json'),
        'diskObserver': resources.observer,
        'target': {'container': names['mysql'], 'volume': names['mysqlVolume'],
            'image': config['images']['mysql'], 'redisImage': config['images']['redis'],
            'mysqlPort': config['ports']['mysql'], 'network': names['network'],
            'memoryMiB': allocation['mysqlMemoryMiB'], 'bufferPoolMiB': allocation['mysqlBufferPoolMiB']},
        'capacity': {key: config['capacity'][key] for key in
                     ('reserveHostBytes', 'reserveDockerBytes', 'requiredDatabaseBytes')} | {'maxBackupBytes': 0},
        'operationTimeoutSeconds': config['timeouts']['mysqlOperationSeconds'],
        'appStartupTimeoutSeconds': config['timeouts']['appStartupSeconds'], 'writerPorts': config['writerPorts']}


@contextlib.contextmanager
def database_password(value):
    # An API authorization header must never borrow this JDBC variable.
    previous = os.environ.get(PASSWORD_ENV)
    os.environ[PASSWORD_ENV] = value
    try:
        yield
    finally:
        if previous is None:
            os.environ.pop(PASSWORD_ENV, None)
        else:
            os.environ[PASSWORD_ENV] = previous


def baseline_config(config, info, identity):
    environment = restore.connection_environment(info)
    value = {'releaseDirectory': config['release'], 'datasetId': config['datasetId'],
        'consumerManifestSha256': config['consumerManifestSha256'], 'checksSha256': config['checksumsSha256'],
        'migrationDirectory': str(Path(config['backendRoot']) / 'src/main/resources/db/migration'),
        'appJar': config['appJar'], 'allowSmallQualification': False,
        'sourceTimeoutSeconds': config['operationTimeoutSeconds'],
        'mysql': {'jdbcUrl': environment['AIRBOB_ETL_DB_URL'], 'username': 'root',
                  'passwordEnvironment': PASSWORD_ENV, 'expectedServerUuid': identity['uuid']}}
    return value, environment['AIRBOB_ETL_DB_PASSWORD']


def capture_search_baseline(config, output, runtime, info, db):
    """Trusted internal callback; neither JSON config nor a shell selects its code."""
    identity = db.identity()
    require(identity['superReadOnly'] == 1 and identity['readOnly'] == 1, 'Freeze the exact restored baseline before preparation')
    target = config['target']
    volume = restore.inspect('volume', target['volume'])
    claim = (volume.get('Labels') or {}).get(CLAIM, '')
    require(info['Name'] == '/' + target['container'] and info['Image'] == target['image']
            and (info['Config'].get('Labels') or {}).get('airbob.dataset.id') == config['datasetId']
            and re.fullmatch(r'[0-9a-f]{32}', claim)
            and volume['Driver'] == 'local' and not volume.get('Options')
            and (volume.get('Labels') or {}).get('airbob.dataset.id') == config['datasetId']
            and len(info['Mounts']) == 1 and info['Mounts'][0].get('Name') == target['volume']
            and info['Mounts'][0].get('Type') == 'volume' and info['Mounts'][0]['Destination'] == '/var/lib/mysql',
            'Fresh baseline MySQL identity/sole volume differs')
    adoption = {'containerId': info['Id'], 'container': target['container'], 'image': info['Image'],
                'mysqlServerUuid': identity['uuid'], 'volume': volume['Name'], 'volumeClaim': claim,
                'volumeCreatedAt': volume['CreatedAt'], 'verifiedBeforeCredentialsAndFreePreparation': True}
    write_new(output / 'mysql-before-preparation.json', adoption)
    source, password = baseline_config(config, info, identity)
    write_new(output / 'search-baseline-config.json', source)
    with database_password(password):
        search.capture_baseline(source, output / 'search-baseline')
    return {'searchBaselineReceipt': output / 'search-baseline/baseline-receipt.json',
            'searchBaselineFingerprint': output / 'search-baseline/mysql-baseline-fingerprint.json',
            'mysqlBeforePreparation': output / 'mysql-before-preparation.json'}


def verify_callback_receipt(receipt, output, mysql):
    names = {'searchBaselineReceipt': 'search-baseline/baseline-receipt.json',
             'searchBaselineFingerprint': 'search-baseline/mysql-baseline-fingerprint.json',
             'mysqlBeforePreparation': 'mysql-before-preparation.json'}
    artifacts = receipt['beforePreparationArtifacts']
    require(set(artifacts) == set(names), 'Actual before-preparation search evidence is missing')
    for name, file in names.items():
        require(artifacts[name] == {'file': file, 'sha256': contract.sha(output / file)}, 'Baseline callback evidence changed')
    observed = contract.read(output / names['mysqlBeforePreparation'])
    require(observed['verifiedBeforeCredentialsAndFreePreparation'] is True
            and all(observed[key] == mysql[key] for key in observed if key != 'verifiedBeforeCredentialsAndFreePreparation'),
            'MySQL identity changed since the pre-credential baseline')


def install_private_accounts(runtime, config):
    tools = Path(runtime) / 'tools'
    expected = contract.read(Path(config['release']) / 'tool-sources.json')
    # Prevent Python's import cache from selecting tools from another sealed run.
    for path in tools.glob('*.py'):
        if path.stem in sys.modules:
            loaded = getattr(sys.modules[path.stem], '__file__', None)
            require(loaded and Path(loaded).resolve() == path.resolve(), 'A preparation module is already loaded from a different runtime')
        require(contract.sha(path) == expected[path.name], 'Extracted preparation tool changed')
    sys.path.insert(0, str(tools))
    spec = importlib.util.spec_from_file_location('growth_accounts', tools / 'growth_accounts.py')
    accounts = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(accounts)
    sys.modules['growth_accounts'] = accounts
    destination = Path(config['privateAccounts']).parent
    require(not destination.exists() and not destination.is_symlink(), 'New per-environment account credentials are required')
    accounts.create_private_credentials(contract.read(Path(config['release']) / 'accounts.json'), destination,
                                        restore.account_environment(config))
    contract.validate_private_accounts(config['privateAccounts'], Path(config['release']),
                                       expected_environment=restore.account_environment(config))
    require(stat.S_IMODE(destination.stat().st_mode) == 0o700
            and all(stat.S_IMODE(path.stat().st_mode) == 0o600 and not path.is_symlink() for path in destination.iterdir()),
            'All newly generated account material must be private from its first byte')


def search_config(config, fresh, info, identity, es_config, native):
    source, password = baseline_config(fresh, info, identity)
    source.update(sourceTimeoutSeconds=config['timeouts']['searchSourceSeconds'],
                  snapshotTimeoutSeconds=config['timeouts']['snapshotSeconds'], elasticsearch=es_config,
                  snapshotRelease=config['datasetId'] + '-search-' + config['namespace'][len('airbob-b-local-'):],
                  repository={'name': config['namespace'] + '-native', 'type': 'fs',
                              'settings': {'location': '/backup'}, 'inventoryRoot': str(native)})
    return source, password


def seal_producer(companion, descriptor_path, config_path, prepared_fingerprint, producer, output):
    descriptor = contract.read(descriptor_path)
    manifest, reference = search.validate_companion(companion, descriptor)
    require(reference['elasticsearch']['clusterUuid'] == producer['clusterUuid']
            and reference['elasticsearch']['imageId'] == producer['image']
            and contract.read(companion / 'mysql-prepared-fingerprint.json') == contract.read(prepared_fingerprint),
            'Producer source identity/prepared fingerprint changed')
    native = search.repository_for(contract.read(config_path)).inventory()
    require(native == contract.read(companion / 'native-inventory.json'), 'Native repository changed before sealing')
    record = {'schemaVersion': 1, 'state': 'LOCAL_NATIVE_PRODUCER_SAFE_TO_REMOVE', 'datasetId': manifest['datasetId'],
              'descriptor': binding(descriptor_path), 'companionDirectory': str(companion),
              'producerConfig': binding(config_path), 'preparedFingerprint': binding(prepared_fingerprint),
              'producer': producer, 'nativeInventorySha256': search.digest(native),
              'createdAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'nativeRepositoryRetained': True}
    write_new(output, record)
    sealed = binding(output)
    verify_producer_seal(sealed)
    return sealed


def verify_producer_seal(seal):
    value = bound_input(seal)
    require(value['schemaVersion'] == 1 and value['state'] == 'LOCAL_NATIVE_PRODUCER_SAFE_TO_REMOVE'
            and value['nativeRepositoryRetained'] is True, 'Producer removal requires sealed real native roundtrip evidence')
    descriptor = bound_input(value['descriptor'])
    config = bound_input(value['producerConfig'])
    prepared = bound_input(value['preparedFingerprint'])
    companion = Path(value['companionDirectory'])
    manifest, reference = search.validate_companion(companion, descriptor)
    require(config['repository']['type'] == 'fs' and config['repository']['settings'] == {'location': '/backup'},
            'Only the task filesystem repository is admitted')
    require(all(config['elasticsearch'][key] == value['producer'][key] for key in ('container', 'image', 'url')),
            'Sealed producer configuration points to a different resource')
    inventory = search.repository_for(config).inventory()
    require(value['datasetId'] == manifest['datasetId'] and value['producer']['clusterUuid'] == reference['elasticsearch']['clusterUuid']
            and value['producer']['image'] == reference['elasticsearch']['imageId']
            and value['nativeInventorySha256'] == search.digest(inventory)
            and inventory == contract.read(companion / 'native-inventory.json')
            and prepared == contract.read(companion / 'mysql-prepared-fingerprint.json'), 'Sealed producer/native inputs changed')
    return value


def verify_native_result(result, reference, producer, service, mysql):
    require(result['state'] == 'SEARCH_RESTORED_AND_ACTIVATED' and result['allDocumentSourceFieldsEqual'] is True
            and result['repositoryReadOnly'] is True and result['nativeInventoryUnchanged'] is True
            and result['repositoryRegistrationRemoved'] is True and result['previousIndexRetained'] is None,
            'A fresh native restore and activation must pass every production gate')
    require(result['elasticsearch']['clusterUuid'] == service['clusterUuid'] != producer['clusterUuid']
            and result['elasticsearch']['imageId'] == service['image'] == producer['image']
            and result['mysql']['serverUuid'] == mysql['mysqlServerUuid']
            and result['fullDocumentFingerprint'] == reference['fingerprint']
            and result['activeAlias'] == result['restoredIndex']
            and re.fullmatch(search.INDEX_RE, result['activeAlias']),
            'The new ES cluster, original prepared MySQL, full fields/mapping or versioned alias differ')


def tree_size(root):
    """Read allocated/logical bytes without copying data or following a link."""
    logical = allocated = count = 0
    if not Path(root).exists():
        return {'files': 0, 'logicalBytes': 0, 'allocatedBytes': 0}
    for directory, dirs, files in os.walk(root, followlinks=False):
        require(not any((Path(directory) / name).is_symlink() for name in dirs), 'Measurement tree contains a directory symlink')
        for name in files:
            try:
                entry = (Path(directory) / name).lstat()
            except FileNotFoundError:
                continue  # An atomic report rename may race this sampled observation.
            require(stat.S_ISREG(entry.st_mode), 'Measurement tree contains a non-regular file')
            count += 1
            require(count <= 200000, 'Measurement metadata limit exceeded')
            logical += entry.st_size
            allocated += entry.st_blocks * 512
    return {'files': count, 'logicalBytes': logical, 'allocatedBytes': allocated}


class Measurements:
    def __init__(self, output, capacity):
        self.output, self.capacity = output, capacity
        self.phase = 'initial'; self.source = None
        self.lock = threading.RLock(); self.stop_event = threading.Event(); self.thread = None
        self.samples = []; self.errors = []; self.phases = []
        self.path = output / 'disk-samples.jsonl'
        self.stream = self.path.open('x', encoding='utf-8')
        self.path.chmod(0o600)

    def sample(self):
        with self.lock:
            observed = {'at': dt.datetime.now(dt.timezone.utc).isoformat(), 'phase': self.phase,
                        'hostFreeBytes': shutil.disk_usage(self.output).free,
                        'ownedOutput': tree_size(self.output), 'nativeRepository': tree_size(self.output / 'native-repository')}
            if self.source:
                observed['dockerFreeBytes'] = int(restore.docker('exec', self.source, 'df', '-P', '-k', '/',
                                                    timeout=30).splitlines()[-1].split()[3]) * 1024
                observed['dockerObservationContainerId'] = self.source
            self.samples.append(observed)
            self.stream.write(json.dumps(observed, sort_keys=True) + '\n'); self.stream.flush()
            return observed

    def start(self, source):
        self.source = source
        self.sample()
        def sample_loop():
            while not self.stop_event.wait(10):
                try:
                    self.sample()
                except Exception as error:
                    self.errors.append({'phase': self.phase, 'errorType': type(error).__name__})
        self.thread = threading.Thread(target=sample_loop, name='local-b-disk-sampler', daemon=True)
        self.thread.start()

    def gate(self, phase):
        self.check()
        sample = self.sample()
        result = capacity_gate(self.capacity, sample['hostFreeBytes'], sample['dockerFreeBytes'], phase)
        write_new(self.output / ('capacity-' + phase + '.json'), result)
        return result

    def check(self):
        require(not self.errors, 'Disk measurement failed; resources will be retained')
        require(all(row['hostFreeBytes'] >= self.capacity['reserveHostBytes'] and
                    row.get('dockerFreeBytes', self.capacity['reserveDockerBytes']) >= self.capacity['reserveDockerBytes']
                    for row in self.samples), 'A sampled disk reserve was crossed; resources will be retained')

    @contextlib.contextmanager
    def stage(self, name):
        self.phase = name
        began = dt.datetime.now(dt.timezone.utc); monotonic = time.monotonic()
        entry = {'phase': name, 'startedAt': began.isoformat(), 'startedUtcDate': began.date().isoformat()}
        try:
            self.sample()
            yield
            self.sample(); self.check()
            entry['passed'] = True
        except BaseException as error:
            entry.update(passed=False, errorType=type(error).__name__)
            raise
        finally:
            ended = dt.datetime.now(dt.timezone.utc)
            entry.update(endedAt=ended.isoformat(), endedUtcDate=ended.date().isoformat(),
                         crossedUtcDate=began.date() != ended.date(), elapsedSeconds=round(time.monotonic() - monotonic, 3))
            self.phases.append(entry)
            restore.write(self.output / 'phase-times.json', self.phases)

    def finish(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=35)
            if self.thread.is_alive():
                self.errors.append({'errorType': 'SamplerStopTimeout'})
        self.stream.close()
        hosts = [row['hostFreeBytes'] for row in self.samples]
        docker = [row['dockerFreeBytes'] for row in self.samples if 'dockerFreeBytes' in row]
        return {'schemaVersion': 1, 'intervalSeconds': 10, 'sampleCount': len(self.samples),
                'samplingComplete': not self.errors, 'errors': self.errors, 'sampleLog': binding(self.path),
                'minimumObservedHostFreeBytes': min(hosts) if hosts else None,
                'minimumObservedDockerFreeBytes': min(docker) if docker else None,
                'maximumObservedHostFreeDecreaseBytes': max(0, hosts[0] - min(hosts)) if hosts else None,
                'maximumObservedDockerFreeDecreaseBytes': max(0, docker[0] - min(docker)) if docker else None,
                'maximumObservedOwnedOutputAllocatedBytes': max((r['ownedOutput']['allocatedBytes'] for r in self.samples), default=0),
                'maximumObservedNativeRepositoryBytes': max((r['nativeRepository']['logicalBytes'] for r in self.samples), default=0),
                'phases': self.phases,
                'interpretation': 'Sampled observations, not exact peaks. Host free includes Docker VM and unrelated activity. '
                    'Owned output already includes the native repository. Reviewed reserve/budget values are separate. '
                    'Phase gates and production native disk gates run in addition to sampling.'}


def measure_mysql(resources, prepared):
    info, db = resources.verify_mysql()
    began = time.monotonic()
    settings = db.rows('SELECT @@version version,@@innodb_page_size pageBytes,@@innodb_buffer_pool_size bufferPoolBytes,@@global.time_zone timeZone')[0]
    spaces = db.rows("SELECT NAME,FILE_SIZE,ALLOCATED_SIZE,PAGE_SIZE FROM information_schema.INNODB_TABLESPACES WHERE NAME LIKE 'airbobdb/%' ORDER BY NAME")
    indexes = db.rows("SELECT table_name,index_name,stat_name,stat_value,last_update FROM mysql.innodb_index_stats WHERE database_name='airbobdb' AND stat_name IN ('size','n_leaf_pages') ORDER BY table_name,index_name,stat_name")
    sizes = db.rows("SET SESSION information_schema_stats_expiry=0; SELECT table_name,data_length,index_length,data_free FROM information_schema.tables WHERE table_schema='airbobdb' ORDER BY table_name")
    require(spaces and indexes and {row['table_name'] for row in sizes} == set(prepared['tables']), 'Complete prepared MySQL size statistics are missing')
    allocated = int(restore.docker('exec', resources.mysql['containerId'], 'du', '-sk', '/var/lib/mysql', timeout=120).split()[0]) * 1024
    return {'settings': settings, 'containerMemoryLimitBytes': info['HostConfig']['Memory'],
            'tablespaces': spaces, 'allocatedTablespaceBytes': sum(row['ALLOCATED_SIZE'] for row in spaces),
            'dataDirectoryAllocatedBytes': allocated, 'tableStatistics': sizes, 'persistentIndexPageCounts': indexes,
            'exactTableRowsFromPreparedFingerprint': {key: value['rows'] for key, value in prepared['tables'].items()},
            'elapsedSeconds': round(time.monotonic() - began, 3), 'analyzeExecuted': False,
            'interpretation': 'Actual tablespace allocation and persisted optimizer size/leaf-page statistics with update timestamps. '
                              'The frozen DB is not analyzed; page statistics are not an exact live working-set measurement.'}


def measure_es(es, resource):
    nodes = es.api('GET', '/_nodes/jvm')['nodes']
    require(len(nodes) == 1, 'Exactly one ES node is required for allocation measurement')
    jvm = next(iter(nodes.values()))['jvm']
    stores = es.api('GET', '/_stats/store')['_all']['total']['store']['size_in_bytes']
    return {'containerId': resource['containerId'], 'clusterUuid': es.identity()['clusterUuid'],
            'containerMemoryLimitBytes': restore.inspect('container', resource['containerId'])['HostConfig']['Memory'],
            'javaVersion': jvm['version'], 'vmVersion': jvm['vm_version'],
            'heapInitialBytes': jvm['mem']['heap_init_in_bytes'], 'heapMaximumBytes': jvm['mem']['heap_max_in_bytes'],
            'indexStoreBytes': stores}


def freeze_on_failure(resources):
    if resources.mysql is None:
        return {'attempted': False, 'reason': 'The fresh restore adapter owns freezing an unadopted partial target'}
    try:
        # Identity checks remain strict, but an unexpectedly writable owned DB is exactly what this recovery freezes.
        resources.engine()
        info = restore.inspect('container', resources.mysql['containerId'])
        verify_resource(resources.mysql, info, restore.inspect('volume', resources.mysql['volume']), resources.network)
        db = restore.Database(resources.mysql['containerId'])
        require(db.identity()['uuid'] == resources.mysql['mysqlServerUuid'], 'Failure target UUID changed')
        db.execute('SET GLOBAL super_read_only=ON')
        identity = db.identity()
        return {'attempted': True, 'frozen': identity['superReadOnly'] == identity['readOnly'] == 1,
                'containerId': resources.mysql['containerId'], 'mysqlServerUuid': identity['uuid']}
    except BaseException as error:
        return {'attempted': True, 'frozen': False, 'errorType': type(error).__name__}


def completion_payload(config, output, input_config_binding, resources, fresh, receipt, preflight_binding,
                       producer, native_receipt, producer_seal, tool_bindings, start, end):
    """Canonical final-input boundary shared with the independent local service tool."""
    restored = output / 'restore'
    return {'schemaVersion': 1, 'kind': KIND, 'state': STATE, 'datasetId': config['datasetId'],
        'finalScaleSelected': True, 'startedAt': start.isoformat(), 'finishedAt': end.isoformat(),
        'startedUtcDate': start.date().isoformat(), 'finishedUtcDate': end.date().isoformat(),
        'crossedUtcDate': start.date() != end.date(), 'inputConfig': input_config_binding,
        'restoreConfig': binding(output / 'restore-config.json'), 'restorePreflight': preflight_binding,
        'restoreReceipt': binding(restored / 'restore.json'), 'preparedFingerprint': binding(restored / 'prepared-fingerprint.json'),
        'mysql': resources.mysql,
        'elasticsearch': resources.es | {'activeAlias': native_receipt['activeAlias'],
            'restoreReceipt': binding(output / 'search-restore/search-restore-receipt.json')},
        'network': resources.network, 'privateAccounts': fresh['privateAccounts'],
        'serviceEnvironmentFile': receipt['serviceEnvironmentFile'],
        'accountEnvironment': restore.account_environment(fresh), 'producerRemoved': producer,
        'nativeRepository': str(output / 'native-repository'), 'nativeCompanionDirectory': str(output / 'search-companion'),
        'nativeDescriptor': binding(output / 'native-companion-descriptor.json'), 'producerRemovalSeal': producer_seal,
        'hostRuntimeQualification': qualification_binding(output / 'host-runtime-qualification.json'),
        'productionSources': tool_bindings, 'measurements': binding(output / 'measurements.json'),
        'reviewedBudgetsAndReserves': config['capacity'],
        'applicationStartedByCompletion': False, 'qualificationApplicationStopped': True,
        'databaseFrozen': True, 'newServiceElasticsearchRunning': True,
        'maximumConcurrentBusinessDatabases': 1, 'plaintextSqlSpoolBytes': 0,
        'oldResourcesRemoved': False, 'cloudExecution': False,
        'serviceHandoff': 'Use serve-growth-b-local.py with this receipt and its externally reviewed SHA; '
            'credentials are usable for qualification but the persistent application is not started.'}


def complete(config, output, input_config_binding, validated):
    """The caller has authenticated a finished FINAL release; no harness shortcuts."""
    resources = Resources(config, output)
    measurements = Measurements(output, config['capacity'])
    start = dt.datetime.now(dt.timezone.utc)
    tool_bindings = source_bindings()
    run_lock = None
    measurement_result = None
    try:
        require(bound_input(input_config_binding) == config, 'Reviewed configuration changed before runtime execution')
        verify_input_anchors(config)
        runtime = contract.extract_runtime(Path(config['release']), output / 'runtime')
        host_path = output / 'host-runtime-qualification.json'
        qualified = qualify_runtime(Path(config['release']), runtime, host_path,
                                    expected_checks=validated[1], java_home=config['runtime']['javaHome'])
        assert_runtime(qualified, config['release'])
        require(os.stat(tempfile.gettempdir()).st_dev == os.stat(output).st_dev,
                'Production source scratch and output must share the measured host filesystem')
        with activated_runtime(qualified):
            resources.engine()
            run_lock = restore.acquire_fresh_run_lock(runtime)
            write_new(output / 'initial-environment.json', resources.empty())
            resources.start_network_observer()
            measurements.start(resources.observer['id'])
            measurements.gate('initial')
            fresh = fresh_config(config, resources, output)
            install_private_accounts(runtime, fresh)
            fresh_path = output / 'restore-config.json'
            write_new(fresh_path, fresh)
            require(restore.configuration(fresh_path, 'local') == fresh, 'Fresh installer configuration differs')
            restore.validate_inputs(fresh)
            preflight = restore.build_fresh_preflight(fresh, output)
            preflight['hostRuntimeQualification'] = qualification_binding(host_path)
            preflight_path = output / 'restore-preflight.json'
            write_new(preflight_path, preflight)
            preflight_binding = binding(preflight_path)
            restored = output / 'restore'
            restored.mkdir(mode=0o700)
            with measurements.stage('mysql-restore-baseline-credentials-free-readiness'):
                require(bound_input(preflight_binding) == preflight and source_bindings() == tool_bindings,
                        'Sealed preflight or production adapter sources changed before restore')
                receipt = restore.apply_fresh_plan(fresh, preflight, restored, runtime,
                                                  before_preparation=capture_search_baseline)
                receipt['hostRuntimeQualification'] = qualification_binding(host_path)
                restore.write(restored / 'restore.json', receipt)
                resources.adopt_mysql(receipt)
                verify_callback_receipt(receipt, restored, resources.mysql)
            prepared_path = restored / 'prepared-fingerprint.json'
            prepared = contract.read(prepared_path)
            write_new(output / 'mysql-runtime-measurement.json', measure_mysql(resources, prepared))
            with measurements.lock:
                measurements.source = resources.mysql['containerId']
                measurements.sample()
                resources.remove_observer()
            native = output / 'native-repository'
            native.mkdir(mode=0o777)
            native.chmod(0o777)  # Only this public native data subtree; its parent and credentials remain 0700.
            baseline = restored / 'search-baseline'
            companion = output / 'search-companion'
            descriptor_path = output / 'native-companion-descriptor.json'
            measurements.gate('producer')
            with measurements.stage('full-native-producer-and-temporary-restore'):
                es_config, es, producer_identity = resources.start_es('producer', native)
                info, db = resources.verify_mysql()
                producer_config, password = search_config(config, fresh, info, db.identity(), es_config, native)
                producer_config_path = output / 'native-producer-config.json'
                write_new(producer_config_path, producer_config)
                with database_password(password):
                    descriptor = search.produce(producer_config, baseline, companion, build_index=True)
                write_new(descriptor_path, descriptor)
                write_new(output / 'producer-runtime-measurement.json', measure_es(es, resources.es))
                producer_seal = seal_producer(companion, descriptor_path, producer_config_path, prepared_path,
                                              resources.es, output / 'producer-removal-seal.json')
            with measurements.stage('sealed-producer-removal'):
                producer = resources.remove_producer(native, producer_seal)
            measurements.gate('service')
            with measurements.stage('fresh-service-native-restore-and-alias'):
                require(source_bindings() == tool_bindings, 'Production adapter sources changed before service restore')
                es_config, es, service_identity = resources.start_es('service', native)
                require(service_identity['clusterUuid'] != producer_identity['clusterUuid'], 'Service ES must be a genuinely new cluster')
                info, db = resources.verify_mysql()
                service_config, password = search_config(config, fresh, info, db.identity(), es_config, native)
                service_config_path = output / 'native-service-config.json'
                write_new(service_config_path, service_config)
                with database_password(password):
                    native_receipt = search.restore(service_config, companion, descriptor, baseline,
                                                    output / 'search-restore', activate_alias=True)
                verify_native_result(native_receipt, contract.read(companion / 'snapshot-reference.json'),
                                     producer, resources.es, resources.mysql)
                require(es.alias() == native_receipt['activeAlias'], 'Service alias changed after activation')
                verify_resource(resources.es, restore.inspect('container', resources.es['containerId']),
                                restore.inspect('volume', resources.es['volume']), resources.network, native)
                resources.verify_mysql(); resources.exclusive()
                write_new(output / 'service-runtime-measurement.json', measure_es(es, resources.es))
                write_new(output / 'native-repository-measurement.json', tree_size(native))
            with measurements.stage('final-prepared-mysql-continuity'):
                info, _ = resources.verify_mysql()
                final_prepared = restore.fingerprint(runtime, Path(config['release']), info,
                    output / 'final-prepared-fingerprint.json', config['timeouts']['mysqlOperationSeconds'])
                require(final_prepared == prepared, 'Prepared MySQL changed between producer and new service restoration')
                require(es.identity() == service_identity and es.alias() == native_receipt['activeAlias'],
                        'Service search identity/alias changed during the final MySQL comparison')
            measurements.gate('final')
            measurements.check()
            measurement_result = measurements.finish()
            require(measurement_result['samplingComplete'] is True, 'Final disk sampling did not finish cleanly')
            write_new(output / 'measurements.json', measurement_result)
            require(source_bindings() == tool_bindings and binding(input_config_binding['path']) == input_config_binding,
                    'Reviewed completion configuration or production code changed during the run')
            require(bound_input(input_config_binding) == config, 'Final input configuration differs from executed parameters')
            verify_input_anchors(config)
            verify_producer_seal(producer_seal)
            resources.verify_mysql(); resources.exclusive()
            end = dt.datetime.now(dt.timezone.utc)
            result = completion_payload(config, output, input_config_binding, resources, fresh, receipt, preflight_binding,
                                        producer, native_receipt, producer_seal, tool_bindings, start, end)
            write_new(output / 'completion-receipt.json', result)
            write_new(output / 'completion-seal.json', {'schemaVersion': 1, 'state': 'SEALED_LOCAL_COMPLETION',
                'completionReceipt': binding(output / 'completion-receipt.json'),
                'artifacts': {name: binding(output / name) for name in ('initial-environment.json', 'resource-lifetime.json',
                    'mysql-runtime-measurement.json', 'producer-runtime-measurement.json', 'service-runtime-measurement.json',
                    'native-repository-measurement.json', 'capacity-initial.json', 'capacity-producer.json',
                    'capacity-service.json', 'capacity-final.json', 'phase-times.json', 'measurements.json',
                    'final-prepared-fingerprint.json')}})
            return result
    except BaseException as error:
        freeze = freeze_on_failure(resources)
        if measurement_result is None:
            measurement_result = measurements.finish()
        if not (output / 'measurements.json').exists():
            write_new(output / 'measurements.json', measurement_result)
        write_new(output / 'completion-failure.json', {'schemaVersion': 1, 'kind': KIND,
            'state': 'FAILED_UNVERIFIED_RESOURCES_RETAINED', 'datasetId': config['datasetId'],
            'errorType': type(error).__name__, 'failedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
            'startedUtcDate': start.date().isoformat(), 'failedUtcDate': dt.datetime.now(dt.timezone.utc).date().isoformat(),
            'freeze': freeze, 'network': resources.network, 'observer': resources.observer,
            'mysql': resources.mysql, 'elasticsearch': resources.es, 'outputRetained': str(output),
            'cleanupAttempted': False, 'cloudExecution': False,
            'resumePolicy': 'Inspect the retained exact resources and failed phase. A new completion refuses them; '
                'never prune, replay credential preparation blindly, or declare a mismatched projection qualified.'})
        raise
    finally:
        if run_lock is not None:
            run_lock.close()


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--config-sha256', required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args(argv)
    config = configuration(args.config, args.config_sha256)
    input_binding = {'path': str(contract.regular(args.config).resolve()), 'sha256': args.config_sha256}
    output = safe_path(str(args.output), exists=False)
    require(not output.exists() and output.parent.is_dir(), 'Output must be a new directory in an existing parent')
    require(not output.is_relative_to(Path(config['release'])), 'Completion must not modify the sealed release')
    validated = validate_final(config)
    require(bound_input(input_binding) == config, 'Reviewed configuration changed while validating the final release')
    output.mkdir(mode=0o700)
    plan = {'schemaVersion': 1, 'kind': KIND + '-plan', 'state': 'FINAL_INPUTS_AUTHENTICATED_OFFLINE',
            'datasetId': config['datasetId'], 'config': input_binding, 'names': planned_names(config),
            'finalScaleSelected': True, 'productionSources': source_bindings(),
            'plannedFinalState': STATE, 'runtimeAndDockerObserved': False, 'cloudExecution': False,
            'sequence': ['acquire the sealed one-DB runner lock; require the exact empty local engine',
                'create owned network and volume-free disk observer; check live budgets',
                'fresh preflight/apply; full baseline callback before credentials and FREE preparation',
                'retain frozen prepared MySQL; remove only observer',
                'new ES producer; full native produce(build_index=True), temporary restore and seal',
                'remove only sealed producer and its sole volume',
                'new service ES with read-only repository mount; full native restore(activate_alias=True)',
                'retain the same frozen MySQL, new service ES and native repository; issue service handoff']}
    write_new(output / 'completion-plan.json', plan)
    if args.apply:
        result = complete(config, output, input_binding, validated)
        print(json.dumps({'state': result['state'], 'completionReceipt': binding(output / 'completion-receipt.json')}))
    else:
        print(json.dumps({'state': plan['state'], 'plan': binding(output / 'completion-plan.json')}))


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        print(json.dumps({'state': 'FAILED', 'errorType': type(error).__name__}), file=sys.stderr)
        sys.exit(1)
