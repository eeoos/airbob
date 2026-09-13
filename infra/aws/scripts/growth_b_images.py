#!/usr/bin/env python3
"""Closed AWS-only publication of fixed 479 inputs and the separate B connector.

Publication requires the protected main-only GitHub OIDC job. This program never
creates repositories, changes IAM, writes GHCR tags, or starts a Connect worker.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import time

from deterministic_service_bundle import write_archive

ROOT = Path(__file__).resolve().parents[3]
ACCOUNT = '942632789808'
REGION = 'ap-northeast-2'
REGISTRY = f'{ACCOUNT}.dkr.ecr.{REGION}.amazonaws.com'
BUCKET = f'airbob-performance-lab-bundles-{ACCOUNT}'
RUNTIME = '479ba02d54954af46f1f5633aceddeb69e321ff3'
CONTRACT = 'infra/aws/images/global-b-runtime.json'
WORKFLOW = '.github/workflows/aws-growth-b-images.yml'
B_CONTEXT = 'docker/aws-global-b-debezium'
TOOLS = ('infra/aws/scripts/growth_b_images.py',
         'infra/aws/scripts/deterministic_service_bundle.py', CONTRACT, WORKFLOW,
         B_CONTEXT + '/Dockerfile')
REPOSITORIES = {
    'APP_IMAGE': 'airbob-repo', 'REDIS_IMAGE': 'airbob-infra/redis',
    'REDIS_EXPORTER_IMAGE': 'airbob-infra/redis-exporter',
    'NODE_EXPORTER_IMAGE': 'airbob-infra/node-exporter', 'KAFKA_IMAGE': 'airbob-infra/kafka',
    'DEBEZIUM_IMAGE': 'airbob-infra/debezium', 'ELASTICSEARCH_IMAGE': 'airbob-infra/elasticsearch',
    'ELASTICSEARCH_EXPORTER_IMAGE': 'airbob-infra/elasticsearch-exporter',
    'PROMETHEUS_IMAGE': 'airbob-infra/prometheus', 'GRAFANA_IMAGE': 'airbob-infra/grafana',
}
INFRA = tuple(name for name in REPOSITORIES if name != 'APP_IMAGE')
MEDIA = ('application/vnd.oci.image.index.v1+json',
         'application/vnd.docker.distribution.manifest.list.v2+json')
JMX_SHA = 'a95983fd96e865d2bcdf911cc500e7c82808c27ab9fd226bf96732b6c3d8c46e'
PLUGIN_SHA = '6de35d7c20ca1d00e6d9d8ae0e033203e487bf29e335a096dcaf38d4e0316f59'
ARCHIVE_SHA = '14b7c50782b162f1a1a17706ebc05c424995e681ffa1ae7d3a1e1a91345d871a'


class GateError(Exception):
    """A stable public failure code; raw subprocess output is never replayed."""


class HeadUnavailable(GateError):
    """Without ListBucket, a missing key and a denied HEAD both return 403."""


class ConditionalExists(GateError):
    """The service rejected a create-only PUT because the key already exists."""


def require(condition, code):
    if not condition:
        raise GateError(code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    return sha(Path(path).read_bytes())


def utc():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def canonical(data):
    return (json.dumps(data, indent=2, ensure_ascii=True) + '\n').encode()


def write_json(path, data):
    with Path(path).open('xb') as stream:
        stream.write(canonical(data))


def parse(data, code='invalid_json'):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, code)
            result[key] = value
        return result
    try:
        return json.loads(data, object_pairs_hook=unique)
    except (ValueError, TypeError):
        raise GateError(code) from None


def safe_path(name):
    require(isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9_./+-]+', name)
            and all(part not in ('', '.', '..') for part in name.split('/')), 'unsafe_source_path')
    return name


def run(args, *, timeout=120, cwd=None, missing=False):
    try:
        result = subprocess.run([str(arg) for arg in args], cwd=cwd, capture_output=True,
                                timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise GateError('command_unavailable_or_timeout') from None
    if result.returncode:
        if missing and re.search(rb'\((404|NoSuchKey|NotFound)\)', result.stderr):
            return None
        if missing and re.search(rb'\((403|AccessDenied|Forbidden)\)', result.stderr):
            raise HeadUnavailable('s3_head_absence_or_access_unresolved')
        if '--if-none-match' in args and re.search(rb'\((412|PreconditionFailed)\)', result.stderr):
            raise ConditionalExists('conditional_object_already_exists')
        raise GateError('command_failed_' + str(args[0]).rsplit('/', 1)[-1])
    return result.stdout


def git(root, *args):
    return run(['git', '-C', root, *args])


def blob(root, commit, name):
    safe_path(name)
    tree = git(root, 'ls-tree', commit, '--', name).decode().strip()
    require(re.fullmatch(r'100(?:644|755) blob [0-9a-f]{40}\t' + re.escape(name), tree),
            'source_must_be_committed_regular_file')
    return git(root, 'cat-file', 'blob', commit + ':' + name)


def source_gate(root, env, *, workflow):
    head = git(root, 'rev-parse', '--verify', 'HEAD').decode().strip()
    require(re.fullmatch(r'[0-9a-f]{40}', head), 'invalid_execution_commit')
    git(root, 'merge-base', '--is-ancestor', head, 'refs/remotes/origin/main')
    git(root, 'merge-base', '--is-ancestor', RUNTIME, head)
    remote = git(root, 'remote', 'get-url', 'origin').decode().strip()
    require(remote in ('https://github.com/eeoos/airbob', 'https://github.com/eeoos/airbob.git',
                       'git@github.com:eeoos/airbob.git'), 'wrong_source_repository')
    hashes = {}
    for name in TOOLS:
        data = blob(root, head, name)
        path = Path(root) / name
        require(path.resolve() == path.absolute() and path.is_file()
                and path.read_bytes() == data, 'execution_source_differs_from_commit')
        hashes[name] = sha(data)
    if workflow:
        expected = {'GITHUB_ACTIONS': 'true', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                    'GITHUB_REPOSITORY': 'eeoos/airbob', 'GITHUB_REF': 'refs/heads/main',
                    'GITHUB_SHA': head,
                    'AIRBOB_EXPECTED_EXECUTION_COMMIT': head,
                    'GITHUB_WORKFLOW_REF': 'eeoos/airbob/' + WORKFLOW + '@refs/heads/main',
                    'GITHUB_WORKFLOW_SHA': head}
        require(all(env.get(key) == value for key, value in expected.items()),
                'protected_main_workflow_required')
    return {'runtimeCommit': RUNTIME, 'executionCommit': head, 'executionFilesSha256': hashes}


def load_contract(root, identity):
    spec = parse(blob(root, identity['executionCommit'], CONTRACT))
    require(set(spec) == {'schemaVersion', 'runtimeCommit', 'legacyReleaseSha256',
                         'legacyBundleManifestSha256', 'appImage', 'debezium'}
            and spec['schemaVersion'] == 1 and spec['runtimeCommit'] == RUNTIME,
            'invalid_b_image_contract')
    require(spec['appImage'] == REGISTRY + '/airbob-repo@sha256:'
            '442574616c6e5572ee7c9493ccd9ec59eeed629188371a6384513816d589be70',
            'unexpected_runtime_app_image')
    b = spec['debezium']
    require(set(b) == {'repository', 'tagPrefix', 'context', 'dockerfile', 'pluginVersion',
                       'pluginUrl', 'pluginArchiveSha256', 'pluginJarSha256', 'connectVersion', 'buildArgs'}
            and b['repository'] == REPOSITORIES['DEBEZIUM_IMAGE'] and b['tagPrefix'] == 'global-b-'
            and b['context'] == B_CONTEXT and b['dockerfile'] == B_CONTEXT + '/Dockerfile'
            and b['pluginVersion'] == '3.0.8.Final' and b['connectVersion'] == '3.7.0'
            and b['pluginArchiveSha256'] == ARCHIVE_SHA and b['pluginJarSha256'] == PLUGIN_SHA
            and b['pluginUrl'] == 'https://repo.maven.apache.org/maven2/io/debezium/'
            'debezium-connector-mysql/3.0.8.Final/debezium-connector-mysql-3.0.8.Final-plugin.tar.gz',
            'unexpected_b_plugin_contract')
    legacy_bytes = blob(root, RUNTIME, 'infra/aws/images/release.json')
    require(sha(legacy_bytes) == spec['legacyReleaseSha256'] ==
            '14f146c2453065fbc0c8a3e48dd59ac298b962575907e9c826af620de20c7062',
            'legacy_release_changed')
    bundle_bytes = blob(root, RUNTIME, 'infra/aws/bundles/manifest.json')
    require(sha(bundle_bytes) == spec['legacyBundleManifestSha256'] ==
            'c73a37e98a2cb10d210ba3ae39d5c390ac66305825e2b846f70b46ec2bbdb5fb',
            'legacy_bundle_inventory_changed')
    legacy = parse(legacy_bytes)
    require(legacy['platforms'] == ['linux/amd64', 'linux/arm64']
            and len(legacy['infra']) == 9
            and {item['variable'] for item in legacy['infra']} == set(INFRA), 'invalid_legacy_matrix')
    for entry in legacy['infra']:
        require(entry['repository'] == REPOSITORIES[entry['variable']], 'wrong_ecr_repository')
        safe_path(entry['context'])
        safe_path(entry['dockerfile'])
        validate_build_args(entry['buildArgs'])
    legacy_b = next(item for item in legacy['infra'] if item['variable'] == 'DEBEZIUM_IMAGE')
    require(b['buildArgs'] == legacy_b['buildArgs'], 'unexpected_b_base_images')
    return spec, legacy, parse(bundle_bytes)


def validate_build_args(args):
    require(isinstance(args, dict) and bool(args), 'missing_digest_pinned_bases')
    for key, value in args.items():
        require(re.fullmatch(r'[A-Z0-9_]+', key) and isinstance(value, str)
                and re.fullmatch(r'[a-z0-9./_-]+@sha256:[0-9a-f]{64}', value),
                'base_image_must_be_digest_pinned')


def materialize(root, commit, paths, destination):
    """Export only committed regular blobs, never checkout files or local secrets."""
    names = set()
    for name in paths:
        safe_path(name)
        entries = git(root, 'ls-tree', '-r', '--name-only', commit, '--', name).decode().splitlines()
        require(entries, 'empty_source_inventory')
        names.update(entries)
    hashes = {}
    for name in sorted(names):
        data = blob(root, commit, name)
        path = Path(destination) / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('xb') as stream:
            stream.write(data)
        tree_mode = git(root, 'ls-tree', commit, '--', name).decode().split(' ', 1)[0]
        path.chmod(0o755 if tree_mode == '100755' else 0o644)
        hashes[name] = sha(data)
    return hashes


class Cloud:
    def call(self, *args, missing=False):
        result = run(['aws', *args, '--region', REGION, '--no-cli-pager', '--output', 'json'],
                     missing=missing)
        return None if result is None else parse(result, 'invalid_aws_response')

    def identity(self):
        result = self.call('sts', 'get-caller-identity')
        require(result.get('Account') == ACCOUNT and re.fullmatch(
            rf'arn:aws:sts::{ACCOUNT}:assumed-role/airbob-image-publisher/[A-Za-z0-9+=,.@_-]+',
            result.get('Arn', '')), 'image_publisher_oidc_role_required')
        return {'account': ACCOUNT, 'role': 'airbob-image-publisher'}


def image_lookup(cloud, variable, tag, *, allow_missing=False):
    require(variable in REPOSITORIES and (tag == RUNTIME or
            (variable == 'DEBEZIUM_IMAGE' and re.fullmatch(r'global-b-[0-9a-f]{40}', tag))),
            'image_coordinates_outside_closed_contract')
    result = cloud.call('ecr', 'batch-get-image', '--repository-name', REPOSITORIES[variable],
                        '--image-ids', 'imageTag=' + tag, '--accepted-media-types', *MEDIA)
    images, failures = result.get('images'), result.get('failures')
    if allow_missing and images == [] and isinstance(failures, list) and len(failures) == 1:
        failure = failures[0]
        require(failure.get('failureCode') == 'ImageNotFound'
                and failure.get('imageId', {}).get('imageTag') == tag, 'ecr_lookup_failed')
        return None
    require(isinstance(images, list) and len(images) == 1 and failures == [], 'ecr_lookup_failed')
    item = images[0]
    digest = item.get('imageId', {}).get('imageDigest', '')
    require(item.get('registryId') == ACCOUNT and item.get('repositoryName') == REPOSITORIES[variable]
            and item.get('imageId', {}).get('imageTag') == tag
            and re.fullmatch(r'sha256:[0-9a-f]{64}', digest), 'wrong_ecr_image_identity')
    raw = item.get('imageManifest', '')
    require(isinstance(raw, str) and 'sha256:' + sha(raw.encode()) == digest, 'ecr_manifest_digest_mismatch')
    manifest = parse(raw, 'invalid_image_manifest')
    children = platform_children(manifest)
    return {'image': REGISTRY + '/' + REPOSITORIES[variable] + '@' + digest,
            'tag': tag, 'digest': digest, 'platforms': children}


def platform_children(manifest):
    require(manifest.get('mediaType') in MEDIA and isinstance(manifest.get('manifests'), list),
            'multiarch_image_required')
    children = {}
    for member in manifest['manifests']:
        platform = member.get('platform', {})
        os_name, arch = platform.get('os'), platform.get('architecture')
        if os_name == arch == 'unknown':
            require(member.get('annotations', {}).get('vnd.docker.reference.type') == 'attestation-manifest',
                    'unknown_non_attestation_platform')
            continue
        require(os_name == 'linux' and arch in ('amd64', 'arm64') and arch not in children
                and re.fullmatch(r'sha256:[0-9a-f]{64}', member.get('digest', '')),
                'unexpected_image_platform')
        children[arch] = member['digest']
    require(set(children) == {'amd64', 'arm64'}, 'both_linux_platforms_required')
    return children


def image_metadata(image, revision, runner=run):
    result = {}
    repo = image['image'].split('@')[0]
    for arch, digest in sorted(image['platforms'].items()):
        config = parse(runner(['docker', 'buildx', 'imagetools', 'inspect', '--format',
                               '{{json .Image}}', repo + '@' + digest]))
        require(config.get('architecture') == arch and config.get('os') == 'linux'
                and config.get('config', {}).get('Labels', {}).get('org.opencontainers.image.revision') == revision,
                'image_revision_or_platform_mismatch')
        result[arch] = {'manifestDigest': digest, 'revision': revision}
    return result


def runtime_probe(variable, image, *, b=False, runner=run):
    """Inspect files with no network; this is not a live REST/CDC health receipt."""
    if variable not in ('KAFKA_IMAGE', 'DEBEZIUM_IMAGE', 'ELASTICSEARCH_IMAGE'):
        return {'kind': 'metadata-only'}
    runner(['docker', 'pull', '--platform', 'linux/amd64', image], timeout=600)
    if b:
        command = ('sha256sum /opt/kafka/connect-plugins/debezium-mysql/'
                   'debezium-connector-mysql-3.0.8.Final.jar /opt/jmx/jmx_prometheus_javaagent.jar; '
                   'test -s /opt/kafka/libs/connect-runtime-3.7.0.jar; '
                   'test -s /opt/kafka/libs/kafka_2.13-3.7.0.jar; '
                   'test "$(find /opt/kafka/connect-plugins -name \'debezium-connector-mysql-*.jar\' | wc -l)" -eq 1')
    elif variable == 'ELASTICSEARCH_IMAGE':
        command = ('test -f /usr/share/elasticsearch/modules/repository-s3/plugin-descriptor.properties; '
                   '/usr/share/elasticsearch/bin/elasticsearch-plugin list | grep -Fx analysis-nori; '
                   '/usr/share/elasticsearch/bin/elasticsearch --version')
    elif variable == 'DEBEZIUM_IMAGE':
        command = ('test -s /opt/jmx/jmx_prometheus_javaagent.jar; '
                   'test -s /opt/kafka/connect-plugins/debezium-mysql/debezium-connector-mysql-2.6.1.Final.jar')
    else:
        command = 'test -s /opt/jmx/jmx_prometheus_javaagent.jar'
    output = runner(['docker', 'run', '--rm', '--platform', 'linux/amd64', '--network', 'none',
                     '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                     '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m', '--entrypoint', '/bin/bash', image,
                     '-euc', command], timeout=180).decode()
    result = {'kind': 'network-none-files', 'platform': 'linux/amd64', 'connectWorkerStarted': False}
    if b:
        lines = output.splitlines()
        expected = [PLUGIN_SHA + '  /opt/kafka/connect-plugins/debezium-mysql/'
                    'debezium-connector-mysql-3.0.8.Final.jar',
                    JMX_SHA + '  /opt/jmx/jmx_prometheus_javaagent.jar']
        require(lines == expected, 'b_plugin_or_jmx_hash_mismatch')
        result.update(pluginVersion='3.0.8.Final', pluginJarSha256=PLUGIN_SHA,
                      connectVersion='3.7.0', connectVersionEvidence='runtime-and-kafka-jar-filenames',
                      restVersionMeasured=False, jmxSha256=JMX_SHA)
    elif variable == 'ELASTICSEARCH_IMAGE':
        require('analysis-nori' in output.splitlines()
                and re.search(r'Version: 8\.18\.8(?:,|\s)', output), 'elasticsearch_native_version_mismatch')
        result.update(elasticsearchVersion='8.18.8', repositoryS3=True, analysisNori=True)
    return result


def publish_image(root, identity, spec, legacy, cloud, variable, *, b=False, runner=run):
    require(variable in INFRA and (not b or variable == 'DEBEZIUM_IMAGE'), 'image_not_publishable')
    commit = identity['executionCommit'] if b else RUNTIME
    tag = 'global-b-' + commit if b else commit
    entry = spec['debezium'] if b else next(item for item in legacy['infra'] if item['variable'] == variable)
    existing = image_lookup(cloud, variable, tag, allow_missing=True)
    source_hashes = {}
    with tempfile.TemporaryDirectory(prefix='airbob-image-source-') as directory:
        source_hashes = materialize(root, commit, [entry['context'], entry['dockerfile']], directory)
        if existing is None:
            args = ['docker', 'buildx', 'build', '--platform', 'linux/amd64,linux/arm64',
                    '--file', str(Path(directory) / entry['dockerfile']),
                    '--tag', REGISTRY + '/' + REPOSITORIES[variable] + ':' + tag,
                    '--label', 'org.opencontainers.image.revision=' + commit, '--push']
            validate_build_args(entry['buildArgs'])
            for key, value in sorted(entry['buildArgs'].items()):
                args.extend(['--build-arg', key + '=' + value])
            args.append(str(Path(directory) / entry['context']))
            runner(args, timeout=5400)
    image = None
    for attempt in range(5):
        image = image_lookup(cloud, variable, tag, allow_missing=True)
        if image is not None:
            break
        if attempt < 4:
            time.sleep(2)
    require(image is not None, 'published_image_not_readable')
    if existing:
        require(image['digest'] == existing['digest'], 'immutable_image_changed')
    image['metadata'] = image_metadata(image, commit, runner)
    image['probe'] = runtime_probe(variable, image['image'], b=b, runner=runner)
    image.update(variable=variable, sourceCommit=commit, sourceFilesSha256=source_hashes,
                 reused=existing is not None)
    if b:
        image['debezium'] = {'image': image['image'], 'buildCommit': commit, 'pluginVersion': '3.0.8.Final',
                             'pluginJarSha256': PLUGIN_SHA, 'connectVersion': '3.7.0'}
    return image


def package_bundle(root, bundle_inventory, destination, runner=run):
    names = bundle_inventory['files']
    require(len(names) == len(set(names)) == 19, 'invalid_bundle_inventory')
    validation = ['infra/aws/bundles/manifest.json', 'infra/aws/tests/all-service-bundles-test.sh',
                  'infra/aws/scripts/verify-service-bundle.sh', 'infra/aws/tests/fixtures/images.env',
                  'infra/aws/tests/fixtures/runtime.env']
    with tempfile.TemporaryDirectory(prefix='airbob-bundle-source-') as directory:
        hashes = materialize(root, RUNTIME, names + validation, directory)
        runner(['bash', str(Path(directory) / validation[1]), '--validate-only'])
        timestamp = int(git(root, 'show', '-s', '--format=%ct', RUNTIME).strip())
        archive_name = 'airbob-service-bundles-' + RUNTIME + '.tar.gz'
        archive = Path(destination) / archive_name
        write_archive(directory, names, archive, timestamp)
        with tarfile.open(archive) as container:
            members = container.getmembers()
            require([member.name for member in members] == names, 'bundle_tar_inventory_changed')
            for member in members:
                require(member.isfile() and sha(container.extractfile(member).read()) == hashes[member.name],
                        'bundle_tar_bytes_changed')
    digest = file_sha(archive)
    checksum = Path(destination) / (archive_name + '.sha256')
    with checksum.open('xb') as stream:
        stream.write((digest + '  ' + archive_name + '\n').encode())
    marker = Path(destination) / ('airbob-service-bundles-' + RUNTIME + '.manifest.json')
    write_json(marker, {'schemaVersion': 1, 'commit': RUNTIME, 'archive': archive_name,
                        'sha256': digest, 'files': names})
    return [archive, checksum, marker], {name: hashes[name] for name in names}


def version(value):
    require(isinstance(value, str) and value not in ('', 'null')
            and re.fullmatch(r'[A-Za-z0-9._+/=-]{1,1024}', value), 's3_version_required')
    return value


def verify_object(cloud, local, key, head, *, directory):
    require(head.get('ContentLength') == local.stat().st_size
            and head.get('ServerSideEncryption') == 'AES256'
            and head.get('Metadata', {}).get('commit') == RUNTIME, 's3_metadata_mismatch')
    pinned = version(head.get('VersionId'))
    target = Path(directory) / ('read-' + local.name)
    result = cloud.call('s3api', 'get-object', '--bucket', BUCKET, '--key', key, str(target))
    # This role has GetObject, not GetObjectVersion. Require the GET response's
    # version to equal the immutable HEAD/PUT pin, then verify its complete bytes.
    require(version(result.get('VersionId')) == pinned and result.get('ContentLength') == local.stat().st_size
            and file_sha(target) == file_sha(local), 's3_exact_response_readback_failed')
    return {'key': key, 'versionId': pinned, 'sha256': file_sha(local), 'bytes': local.stat().st_size,
            'verification': 'get-response-version-equals-pin-and-full-sha256'}


def publish_bundle(cloud, paths, *, create):
    base = 'airbob-service-bundles-' + RUNTIME
    require([path.name for path in paths] == [base + '.tar.gz', base + '.tar.gz.sha256', base + '.manifest.json'],
            'bundle_marker_must_be_last')
    prefix = 'service-bundles/' + RUNTIME + '/'
    with tempfile.TemporaryDirectory(prefix='airbob-bundle-readback-') as directory:
        unresolved = object()
        heads = []
        # Check every existing object for conflicts before publishing anything.
        for local in paths:
            try:
                head = cloud.call('s3api', 'head-object', '--bucket', BUCKET,
                                  '--key', prefix + local.name, missing=True)
            except HeadUnavailable:
                # Never claim that 403 proves absence. The only permitted next
                # write is conditional and cannot replace an existing object.
                head = unresolved
            heads.append(head)
            if head is not None and head is not unresolved:
                verify_object(cloud, local, prefix + local.name, head, directory=directory)
        require(heads[-1] is None or heads[-1] is unresolved
                or all(head is not None and head is not unresolved for head in heads),
                'completion_marker_without_payloads')
        result = []
        for local, head in zip(paths, heads):
            key = prefix + local.name
            if local == paths[-1]:
                for previous in result:
                    current = cloud.call('s3api', 'head-object', '--bucket', BUCKET, '--key', previous['key'])
                    require(version(current.get('VersionId')) == previous['versionId'],
                            'payload_version_changed_before_marker')
            if head is None or head is unresolved:
                require(create, 'published_bundle_missing')
                try:
                    reply = cloud.call('s3api', 'put-object', '--bucket', BUCKET, '--key', key,
                                       '--body', str(local), '--server-side-encryption', 'AES256',
                                       '--content-type', 'application/gzip' if local.name.endswith('.gz') else
                                       ('application/json' if local.name.endswith('.json') else 'text/plain'),
                                       '--metadata', 'commit=' + RUNTIME, '--if-none-match', '*')
                    pinned = version(reply.get('VersionId'))
                except ConditionalExists:
                    pinned = None
                head = cloud.call('s3api', 'head-object', '--bucket', BUCKET, '--key', key)
                if pinned is not None:
                    require(version(head.get('VersionId')) == pinned, 's3_version_changed_after_put')
            result.append(verify_object(cloud, local, key, head, directory=directory))
        # Freeze all response coordinates once more after the completion marker.
        for entry in result:
            head = cloud.call('s3api', 'head-object', '--bucket', BUCKET, '--key', entry['key'])
            require(version(head.get('VersionId')) == entry['versionId'], 's3_version_changed_after_marker')
    return {'bucket': BUCKET, 'prefix': prefix, 'objects': result, 'completionMarker': result[-1],
            'versionHistoryAudit': 'requires-separate-readonly-operator-audit'}


def verify_release(identity, spec, cloud, runner=run):
    images = {}
    for variable in REPOSITORIES:
        found = image_lookup(cloud, variable, RUNTIME)
        if variable == 'APP_IMAGE':
            require(found['image'] == spec['appImage'], 'runtime_app_digest_changed')
        found['metadata'] = image_metadata(found, RUNTIME, runner)
        images[variable] = found
    b_image = image_lookup(cloud, 'DEBEZIUM_IMAGE', 'global-b-' + identity['executionCommit'])
    b_image['metadata'] = image_metadata(b_image, identity['executionCommit'], runner)
    b_image['probe'] = runtime_probe('DEBEZIUM_IMAGE', b_image['image'], b=True, runner=runner)
    fragment = {'image': b_image['image'], 'buildCommit': identity['executionCommit'],
                'pluginVersion': '3.0.8.Final', 'pluginJarSha256': PLUGIN_SHA, 'connectVersion': '3.7.0'}
    return {'runtimeImages': images, 'debezium': fragment, 'bImageVerification': b_image}


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('plan', 'publish-infra', 'publish-b', 'publish-bundle', 'verify-release'))
    parser.add_argument('--variable', choices=INFRA)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    started, before = utc(), time.monotonic()
    try:
        require((args.action == 'publish-infra') == (args.variable is not None), 'unexpected_image_variable')
        identity = source_gate(ROOT, os.environ, workflow=args.action != 'plan')
        spec, legacy, inventory = load_contract(ROOT, identity)
        require(args.output.is_dir() and args.output.resolve() == args.output.absolute()
                and not any(args.output.iterdir()), 'empty_regular_output_directory_required')
        evidence = {'schemaVersion': 1, 'kind': 'growth-b-aws-image-inputs', 'action': args.action,
                    'startedAt': started, **identity}
        if args.action != 'plan':
            cloud = Cloud()
            evidence['publisher'] = cloud.identity()
            # An exact existing app is a prerequisite; the route never rebuilds it.
            app = image_lookup(cloud, 'APP_IMAGE', RUNTIME)
            require(app['image'] == spec['appImage'], 'runtime_app_digest_changed')
        if args.action in ('publish-infra', 'publish-b'):
            evidence['image'] = publish_image(ROOT, identity, spec, legacy, cloud,
                                             args.variable or 'DEBEZIUM_IMAGE', b=args.action == 'publish-b')
        else:
            paths, hashes = package_bundle(ROOT, inventory, args.output)
            evidence['bundleSourceFilesSha256'] = hashes
            evidence['bundleFiles'] = [{'name': path.name, 'sha256': file_sha(path), 'bytes': path.stat().st_size}
                                       for path in paths]
            if args.action == 'plan':
                evidence['infraVariables'] = list(INFRA)
                evidence['appImage'] = spec['appImage']
                evidence['debeziumTag'] = 'global-b-' + identity['executionCommit']
            elif args.action == 'publish-bundle':
                # The marker is published only after all ten general tags and B
                # have passed their immutable metadata and B runtime gates.
                evidence.update(verify_release(identity, spec, cloud))
                evidence['bundle'] = publish_bundle(cloud, paths, create=True)
            else:
                evidence.update(verify_release(identity, spec, cloud))
                evidence['bundle'] = publish_bundle(cloud, paths, create=False)
        evidence.update(completedAt=utc(), elapsedSeconds=round(time.monotonic() - before, 3))
        write_json(args.output / 'receipt.json', evidence)
        print(json.dumps({'status': 'ok', 'action': args.action, 'receiptSha256': file_sha(args.output / 'receipt.json')}))
        return 0
    except (GateError, OSError, KeyError, TypeError, ValueError) as error:
        code = str(error) if isinstance(error, GateError) else 'invalid_local_or_remote_input'
        print(json.dumps({'status': 'failed', 'code': code}), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
