#!/usr/bin/env python3
"""Closed AWS R4 host transport; execute only on the retained Connect host.

The outer reviewed Lab controller owns six-hour OIDC, lease heartbeats, exact
SSM target selection, writer shutdown and restart. This host never changes ASG,
IAM, Docker state, connectors, Elasticsearch, or the full account bundle.
"""
from __future__ import annotations

import argparse
import contextlib
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import socket
import tempfile
import time
import urllib.request

from growth_b_cdc_core import (core, need, Failed, ACCOUNT, REGION, Verifier, read_inputs,
                              journal_binding, source_identity, fields, validate_freeze)
from growth_b_cdc_mysql import PersistentMysql, MysqlStore
import growth_b_aws_restore as restore
import growth_b_service as service


class HostAws:
    ALLOWED = {('sts', 'get-caller-identity'), ('rds', 'describe-db-instances'),
               ('autoscaling', 'describe-auto-scaling-groups'), ('dynamodb', 'get-item'),
               ('secretsmanager', 'get-secret-value')}
    def __init__(self, aws=None): self.aws = aws
    def call(self, *args):
        need(tuple(args[:2]) in self.ALLOWED, 'HOST_AWS_ACTION_NOT_ALLOWED')
        if self.aws is not None: return self.aws.call(*args)
        # Do not let a retained user's profile, ECS selector, or forwarded
        # runner credentials replace this instance's identity. Files are never
        # edited; the override affects only this bounded child invocation.
        environment = {k: v for k, v in os.environ.items() if not k.startswith('AWS_')}
        environment.update(AWS_CONFIG_FILE='/dev/null', AWS_SHARED_CREDENTIALS_FILE='/dev/null', AWS_EC2_METADATA_DISABLED='false',
                           AWS_MAX_ATTEMPTS='1', AWS_RETRY_MODE='standard', AWS_CLI_AUTO_PROMPT='off')
        raw = restore.command(['aws', '--region', REGION, '--no-cli-pager', '--output', 'json', '--cli-connect-timeout', '5',
            '--cli-read-timeout', '30', *args], env=environment, timeout=45)
        return json.loads(raw or '{}')


def tags(items):
    need(isinstance(items, list), 'RESOURCE_TAGS_REQUIRED')
    result = {}
    for item in items:
        need(item['Key'] not in result, 'DUPLICATE_RESOURCE_TAG')
        result[item['Key']] = item['Value']
    return result


def run_tags(config):
    return {'Project': 'airbob', 'Environment': 'performance-lab', 'Stack': 'lab', 'ManagedBy': 'terraform',
        'Persistence': 'ephemeral', 'RunId': config['runId'], 'FencingToken': str(config['resourceFencingToken']), 'ExpiresAt': str(config['expiresAt'])}


def require_tags(actual, config):
    need(all(actual.get(k) == v for k, v in run_tags(config).items()), 'RESOURCE_FENCE_OR_TAG_CHANGED')


def imds_identity():
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), core.reads.NoRedirect())
    base = 'http://169.254.169.254/latest/'
    with opener.open(urllib.request.Request(base + 'api/token', method='PUT', headers={'X-aws-ec2-metadata-token-ttl-seconds': '60'}), timeout=3) as response:
        token = response.read(1024).decode()
    result = {}
    for name, path in [('instanceId', 'meta-data/instance-id'), ('privateIp', 'meta-data/local-ipv4')]:
        with opener.open(urllib.request.Request(base + path, headers={'X-aws-ec2-metadata-token': token}), timeout=3) as response:
            result[name] = response.read(1024).decode()
    return result


class AwsAdapter(core.DockerAdapter):
    def __init__(self, inputs, journal, db, *, aws=None, restart=None, freeze=None, clock=time.monotonic, wall=time.time):
        self.inputs, self.config, self.journal = inputs, inputs['config'], journal
        self.aws, self.clock, self.wall = aws or HostAws(), clock, wall
        self.lease = restore.Lease(self.aws, self.config['lease'])
        self.docker = ['docker', '--host', 'unix:///var/run/docker.sock']
        self.target = self.config['accommodationId']; self.record_cache = {}
        self.runtime, self.restart, self.mode = None, restart, 'running'
        self.freeze, self.freeze_admitted = freeze, False
        self.last_control = -float('inf')
        self.cleanup_end = clock() + min(900, self.config['expiresAt'] - wall())
        self.session_cleanup_end, self.session_cleanup_intent = None, None
        self.host_verified = False
        self.mysql = MysqlStore(lambda: PersistentMysql(self.mysql_argv, guard=self.poll_guard, timeout=20),
                                journal, self.target, self.config['mysqlServerUuid'], fence=self.frozen_fence)
        # The deadline is enforced by a separate local timeout process even if
        # SSM or the Python parent disappears. No orphan lock session may live
        # until RDS's multi-hour wait_timeout.
        self.mysql_argv = ['timeout', '-s', 'TERM', '-k', '3', '120', 'mysql', '--defaults-file=' + str(db.defaults), '--host=' + self.config['rds']['endpoint'],
            '--port=3306', '--ssl-mode=VERIFY_IDENTITY', '--ssl-ca=' + self.config['rds']['caBundle'],
            '--connect-timeout=5', '--default-character-set=utf8mb4', '--batch', '--raw', '--skip-column-names',
            '--skip-reconnect', '--unbuffered', 'airbobdb']

    def business_remaining(self): return min(self.config['businessDeadlineEpoch'] - self.wall(), self.config['expiresAt'] - self.wall())
    def cleanup_remaining(self):
        intent = self.journal.latest_sequence('LOGOUT_INTENT')
        if intent != self.session_cleanup_intent:
            self.session_cleanup_intent = intent
            self.session_cleanup_end = self.clock() + min(60, self.config['expiresAt'] - self.wall())
        return min(self.session_cleanup_end - self.clock(), self.config['expiresAt'] - self.wall())

    def poll_guard(self):
        need(self.wall() < min(self.config['expiresAt'], self.config['approvedExecutionDeadlineEpoch'])
             and self.clock() < self.cleanup_end, 'AWS_CDC_RESOURCE_OR_ACTION_DEADLINE')
        self.control_guard(force=False)

    def inspect_connect(self):
        pin = self.config['hosts']['connect']
        items = core.parse(core.command(self.docker + ['inspect', pin['containerId']], timeout=10))
        need(len(items) == 1, 'EXACT_CONNECT_CONTAINER_REQUIRED'); item = items[0]
        need(item.get('Id') == pin['containerId'] and item.get('Image') == pin['imageId']
             and item.get('Config', {}).get('Image') == pin['image'], 'CONNECT_CONTAINER_OR_IMAGE_CHANGED')
        need(not item['State'].get('Paused') and not item['State'].get('Restarting'), 'CONNECT_STATE_UNSTABLE')
        return item

    def control_guard(self, force=True):
        if not force and self.clock() - self.last_control < 5: return
        self.lease(force=True)
        need(self.wall() < min(self.config['expiresAt'], self.config['approvedExecutionDeadlineEpoch']), 'AWS_RESOURCE_DEADLINE_REACHED')
        values = self.aws.call('rds', 'describe-db-instances', '--db-instance-identifier', self.config['rds']['identifier'])['DBInstances']
        need(len(values) == 1, 'EXACT_RDS_REQUIRED'); item, rds = values[0], self.config['rds']
        need(item['DBInstanceIdentifier'] == rds['identifier'] and item['DbiResourceId'] == rds['resourceId']
             and item['Endpoint'] == {'Address': rds['endpoint'], 'Port': 3306, **({'HostedZoneId': item['Endpoint']['HostedZoneId']} if 'HostedZoneId' in item['Endpoint'] else {})}
             and item['Engine'] == 'mysql' and item['EngineVersion'] == '8.4.11' and item['DBInstanceStatus'] == 'available'
             and item['PubliclyAccessible'] is False and item['MasterUserSecret']['SecretArn'] == rds['masterSecretArn'], 'RDS_TARGET_CHANGED')
        require_tags(tags(item['TagList']), self.config)
        groups = self.aws.call('autoscaling', 'describe-auto-scaling-groups')['AutoScalingGroups']
        groups = [g for g in groups if g['AutoScalingGroupName'].startswith(rds['identifier'] + '-')]
        need(len(groups) == 1, 'RUN_WRITER_ASG_INVENTORY_CHANGED'); group = groups[0]
        expected = self.config['asg']
        need(group['AutoScalingGroupName'] == expected['name'] and group['AutoScalingGroupARN'] == expected['arn']
             and group['LaunchTemplate'] == expected['launchTemplate'], 'EXACT_ASG_LAUNCH_IDENTITY_CHANGED')
        require_tags(tags(group['Tags']), self.config)
        frozen = self.mode in ('frozen', 'cleanup')
        shape = tuple(group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize'))
        if frozen:
            need(shape == (0, 0, 0) and not group['Instances'], 'ALL_APP_WRITERS_MUST_BE_STOPPED')
        else:
            app = self.restart if self.mode == 'restarted' else self.config['hosts']['app']
            need(isinstance(app, dict) and shape == (1, 1, 1) and len(group['Instances']) == 1
                 and group['Instances'][0]['InstanceId'] == app['instanceId']
                 and group['Instances'][0]['LifecycleState'] == 'InService' and group['Instances'][0]['HealthStatus'] == 'Healthy',
                 'VERIFY_APP_INSTANCE_CHANGED')
        connect = self.inspect_connect()
        need(connect['State']['Running'] is (not frozen), 'EXACT_CONNECT_STATE_REQUIRED')
        if frozen:
            need(self.freeze_admitted and connect['State']['FinishedAt'] == self.freeze['connectFinishedAt'], 'FROZEN_CONNECT_IDENTITY_CHANGED')
        if self.mode == 'running':
            need(connect['State']['StartedAt'] == self.config['hosts']['connect']['startedAt'], 'CONNECT_RESTARTED_DURING_VERIFY')
        self.last_control = self.clock()

    def frozen_fence(self):
        need(self.mode in ('frozen', 'cleanup'), 'EXTERNAL_WRITER_FENCE_REQUIRED')
        self.control_guard(force=True)

    def guard(self, mode='running'):
        need(mode in ('running', 'frozen', 'cleanup', 'restarted'), 'INVALID_FENCE_MODE')
        self.mode = mode
        self.local_daemon()
        if mode in ('frozen', 'cleanup') and not self.freeze_admitted:
            from growth_b_cdc_controller import expected_freeze_commands
            validate_freeze(self.freeze, self.config, now=self.wall(), expected_commands=expected_freeze_commands(self.config))
            self.freeze_admitted = True
        if not self.host_verified:
            need(not any(os.environ.get(k) for k in ('AWS_PROFILE', 'AWS_DEFAULT_PROFILE', 'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN')), 'CONNECT_INSTANCE_PROFILE_ONLY')
            need(imds_identity() == {k: self.config['hosts']['connect'][k] for k in ('instanceId', 'privateIp')}, 'RETAINED_CONNECT_HOST_CHANGED')
            need(self.aws.call('sts', 'get-caller-identity').get('Account') == ACCOUNT, 'AWS_ACCOUNT_CHANGED')
            self.host_verified = True
        if mode == 'restarted':
            previous = self.config['hosts']['app']
            need(isinstance(self.restart, dict) and set(self.restart) == set(previous)
                 and self.restart['instanceId'] != previous['instanceId'] and self.restart['containerId'] != previous['containerId']
                 and re.fullmatch(r'i-[0-9a-f]{17}', self.restart['instanceId']) and re.fullmatch(r'[0-9a-f]{64}', self.restart['containerId'])
                 and self.restart['image'] == previous['image'] and self.restart['imageId'] == previous['imageId']
                 and self.restart['startedAt'] != previous['startedAt'] and re.fullmatch(r'[0-9TZ:.-]{20,40}', self.restart['startedAt'])
                 and ipaddress.ip_address(self.restart['privateIp']) in ipaddress.ip_network(self.config['networkCidr']), 'EXPLICIT_NEW_APP_BINDING_REQUIRED')
        self.control_guard(force=True)
        state = self.inspect_connect()['State']
        metadata = core.parse(self.mysql.sql("SELECT JSON_OBJECT('uuid',@@server_uuid,'version',@@version,'binlog',@@global.log_bin,"
            "'sessionBinlog',@@session.sql_log_bin,'format',@@global.binlog_format,'increment',@@auto_increment_increment,'offset',@@auto_increment_offset,"
            "'flyway',(SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1));").strip())
        need(metadata['uuid'] == self.config['mysqlServerUuid'] and metadata['version'].startswith('8.4.11')
             and metadata['binlog'] == metadata['sessionBinlog'] == 1 and metadata['format'] == 'ROW'
             and metadata['increment'] == metadata['offset'] == 1 and metadata['flyway'] == 28, 'MYSQL_IDENTITY_OR_BINLOG_MISMATCH')
        cipher = self.mysql.sql("SHOW SESSION STATUS LIKE 'Ssl_cipher';").strip().split(b'\t')
        need(len(cipher) == 2 and bool(cipher[1]), 'ACTUAL_MYSQL_TLS_REQUIRED')
        app = self.restart if mode == 'restarted' else self.config['hosts']['app']
        self.runtime = {'containers': {'app': {'id': app['containerId'], 'image': app['imageId'], 'startedAt': app['startedAt']},
            'debezium': {'id': self.config['hosts']['connect']['containerId'], 'image': self.config['hosts']['connect']['imageId'], 'startedAt': state['StartedAt']}}}
        return self.runtime

    def endpoint(self, role, port):
        need((role, port) in {('app', 8080), ('debezium', 8083), ('elasticsearch', 9200)}, 'CLOSED_SERVICE_PORT_REQUIRED')
        self.poll_guard()
        if role == 'debezium': return 'http://127.0.0.1:8083'
        host = self.restart if role == 'app' and self.mode == 'restarted' else self.config['hosts'][role]
        return f'http://{host["privateIp"]}:{port}'

    def get(self, role, path):
        connector = self.inputs['manifest']['cdc']['connectorName']
        allowed = role == 'debezium' and path in ('/', '/connector-plugins', '/connectors/' + connector + '/status', '/connectors/' + connector + '/config')
        allowed |= role == 'elasticsearch' and (path in ('/', '/_alias/accommodations') or re.fullmatch(r'/accommodations/_doc/[0-9a-f-]{36}\?_source_includes=name,accommodationId', path))
        need(allowed, 'READ_ENDPOINT_NOT_ALLOWED')
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), core.reads.NoRedirect())
        request = urllib.request.Request(self.endpoint(role, 8083 if role == 'debezium' else 9200) + path,
                                       headers={'Accept': 'application/json', 'Accept-Encoding': 'identity'})
        try:
            with core.warm.wall_clock_limit(10), opener.open(request, timeout=10) as response:
                need(response.status == 200 and response.headers.get('Content-Encoding', 'identity') == 'identity', 'READ_HTTP_STATUS')
                return core.parse(response.read(core.MAX_BYTES + 1))
        except (OSError, ValueError): raise Failed('READ_HTTP_FAILED') from None

    def kafka(self, tool, args):
        need(tool in ('kafka-get-offsets.sh', 'kafka-console-consumer.sh', 'kafka-consumer-groups.sh'), 'KAFKA_TOOL_NOT_ALLOWED')
        # Advertised listener DNS is fixed by the immutable Kafka bundle.
        addresses = {row[4][0] for row in socket.getaddrinfo('kafka.lab.airbob.internal', 9092, type=socket.SOCK_STREAM)}
        need(addresses == {self.config['hosts']['kafka']['privateIp']}, 'KAFKA_PRIVATE_DNS_CHANGED')
        self.poll_guard()
        return core.command(self.docker + ['exec', self.config['hosts']['connect']['containerId'], 'timeout', '-s', 'TERM', '-k', '3', '20',
            '/opt/kafka/bin/' + tool, '--bootstrap-server', 'kafka.lab.airbob.internal:9092'] + args, timeout=28)

    def end_offsets(self, heartbeat=False):
        if heartbeat and self.mode in ('frozen', 'cleanup'):
            self.frozen_fence()
            return {int(k): v for k, v in self.freeze['heartbeat']['offsets'].items()}
        topic = '__debezium-heartbeat.' + self.inputs['manifest']['cdc']['topicPrefix'] if heartbeat else core.TOPIC
        return core.parse_offsets(self.kafka('kafka-get-offsets.sh', ['--topic', topic, '--time', '-1']), topic, 1 if heartbeat else 3)

    def connector_health(self):
        manifest = self.inputs['manifest']; connector = manifest['cdc']['connectorName']
        configuration = self.get('debezium', '/connectors/' + connector + '/config')
        expected = service.connector_config(manifest, self.config['rds']['endpoint'], '')
        # Never print, persist, or compare the secret value. All non-secret
        # connector settings are exact, including the run-specific heartbeat.
        need(set(configuration) in (set(expected), set(expected) | {'name'})
             and all(configuration.get(k) == v for k, v in expected.items() if k != 'database.password')
             and configuration.get('name', connector) == connector, 'CONNECTOR_OUTBOX_ROUTE_CHANGED')
        status = self.get('debezium', '/connectors/' + connector + '/status')
        plugins = self.get('debezium', '/connector-plugins')
        need(self.get('debezium', '/').get('version') == '3.7.0'
             and len([p for p in plugins if p.get('class') == 'io.debezium.connector.mysql.MySqlConnector' and p.get('version') == '3.0.8.Final']) == 1
             and status.get('name') == connector and status.get('connector', {}).get('state') == 'RUNNING'
             and len(status.get('tasks', [])) == 1 and status['tasks'][0].get('state') == 'RUNNING', 'CONNECTOR_RUNTIME_NOT_HEALTHY')
        lines = core.command(self.docker + ['exec', self.config['hosts']['connect']['containerId'], 'sha256sum',
            '/opt/kafka/connect-plugins/debezium-mysql/debezium-connector-mysql-3.0.8.Final.jar',
            '/opt/kafka/connect-plugins/debezium-mysql/debezium-core-3.0.8.Final.jar']).decode().splitlines()
        need(len(lines) == 2 and [x.split()[0] for x in lines] == [core.PLUGIN_JAR_SHA, core.CORE_JAR_SHA], 'PINNED_DEBEZIUM_JARS_REQUIRED')
        es, alias = self.get('elasticsearch', '/'), self.get('elasticsearch', '/_alias/accommodations')
        pin = self.config['hosts']['elasticsearch']
        need(es.get('cluster_uuid') == pin['clusterUuid'] and es.get('version', {}).get('number') == '8.18.8'
             and set(alias) == {pin['indexName']} and alias[pin['indexName']]['aliases']['accommodations'].get('is_write_index') is True, 'EXACT_NATIVE_ES_ALIAS_REQUIRED')
        return {'connectVersion': '3.7.0', 'pluginVersion': '3.0.8.Final', 'coreJarSha256': core.CORE_JAR_SHA,
            'pluginJarSha256': core.PLUGIN_JAR_SHA, 'connector': 'RUNNING', 'tasks': ['RUNNING'], 'elasticsearchVersion': '8.18.8', 'singleWriteAlias': True}

    def snapshot(self): return self.mysql.snapshot()
    def reset_rows(self, baseline, expected): return self.mysql.reset_rows(baseline, expected)
    def reset_counter(self, table, value): return self.mysql.reset_counter(table, value)

    @contextlib.contextmanager
    def cleanup_window(self):
        self.guard('frozen')
        with self.mysql.cleanup_window(): yield


def execute(action, inputs, journal, *, restart=None, freeze=None):
    aws = HostAws()
    need(not any(os.environ.get(k) for k in ('AWS_PROFILE', 'AWS_DEFAULT_PROFILE', 'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN')), 'CONNECT_INSTANCE_PROFILE_ONLY')
    gate = core.DockerAdapter.__new__(core.DockerAdapter)
    gate.docker = ['docker', '--host', 'unix:///var/run/docker.sock']; gate.local_daemon()
    need(imds_identity() == {k: inputs['config']['hosts']['connect'][k] for k in ('instanceId', 'privateIp')}, 'RETAINED_CONNECT_HOST_CHANGED')
    need(aws.call('sts', 'get-caller-identity').get('Account') == ACCOUNT, 'AWS_ACCOUNT_CHANGED')
    restore.Lease(aws, inputs['config']['lease'])(force=True)
    with tempfile.TemporaryDirectory(prefix='.cdc-rds-', dir=journal.path) as directory:
        private = Path(directory); private.chmod(0o700)
        rds = aws.call('rds', 'describe-db-instances', '--db-instance-identifier', inputs['config']['rds']['identifier'])['DBInstances']
        need(len(rds) == 1, 'EXACT_RDS_REQUIRED')
        pin = inputs['config']['rds']; item = rds[0]
        need(item['DbiResourceId'] == pin['resourceId'] and item['DBInstanceIdentifier'] == pin['identifier']
             and item['Endpoint']['Address'] == pin['endpoint'] and item['Endpoint']['Port'] == 3306
             and item['EngineVersion'] == '8.4.11' and item['DBInstanceStatus'] == 'available' and item['PubliclyAccessible'] is False
             and item['MasterUserSecret']['SecretArn'] == pin['masterSecretArn'], 'RDS_TARGET_CHANGED_BEFORE_SECRET_READ')
        require_tags(tags(item['TagList']), inputs['config'])
        # Reuse frozen secret/CA handling; the actual CDC client subsequently
        # uses an exclusive defaults file and explicit VERIFY_IDENTITY flags.
        db, _ = restore.connection(inputs['config'] | {'operationTimeoutSeconds': 20}, aws,
                                   {'masterUsername': rds[0]['MasterUsername']}, private)
        adapter = AwsAdapter(inputs, journal, db, aws=aws, restart=restart, freeze=freeze)
        verifier = Verifier(inputs, adapter, journal)
        try:
            {'preflight': verifier.baseline, 'verify': verifier.verify, 'reset': verifier.reset, 'post-reset': verifier.post_reset}[action]()
            return verifier.public(action, True)
        except BaseException as error:
            verifier.failure = error.code if isinstance(error, (Failed, core.reads.CheckFailed, core.warm.CheckFailed)) else 'AWS_CDC_EXECUTION_UNCONFIRMED'
            journal.add('AWS_EXECUTION_UNCONFIRMED', {'action': action, 'code': verifier.failure})
            return verifier.public(action, False)


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('validate', 'preflight', 'verify', 'reset', 'post-reset'))
    for arg in ('config', 'journal', 'output'): parser.add_argument('--' + arg, type=Path, required=True)
    parser.add_argument('--restart-observation', type=Path)
    parser.add_argument('--freeze-observation', type=Path)
    args = parser.parse_args(argv); journal = None
    try:
        inputs = read_inputs(core.parse(core.reads.read_bytes(args.config)))
        if args.action == 'validate':
            print(json.dumps({'state': 'AWS_CDC_INPUTS_VALIDATED', 'remoteWritesExecuted': False})); return 0
        journal = core.Journal(args.journal, journal_binding(inputs['config']), core.digest(core.encoded(source_identity())))
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, lambda *_: (_ for _ in ()).throw(Failed('INTERRUPTED_WITH_JOURNAL_RETAINED')))
        restart = None if args.restart_observation is None else core.parse(core.reads.read_bytes(args.restart_observation))
        freeze = None if args.freeze_observation is None else core.parse(core.reads.read_bytes(args.freeze_observation))
        report = execute(args.action, inputs, journal, restart=restart, freeze=freeze)
        core.write_new(args.output, core.encoded(report))
        print(json.dumps({'phasePassed': report['phasePassed'], 'receiptSha256': core.digest(core.encoded(report)),
                          'journalHeadSha256': journal.last_sha, 'sourceSnapshotGateSatisfied': False}))
        return 0 if report['phasePassed'] else 1
    except BaseException as error:
        code = error.code if isinstance(error, (Failed, core.reads.CheckFailed, core.warm.CheckFailed)) else 'AWS_CDC_EXECUTION_UNCONFIRMED'
        print(json.dumps({'phasePassed': False, 'failureCode': code, 'privateJournalRetained': journal is not None}))
        return 1
    finally:
        if journal: journal.close()


if __name__ == '__main__': raise SystemExit(main())
