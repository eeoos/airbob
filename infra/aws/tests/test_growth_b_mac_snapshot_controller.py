"""Controller ordering and failure tests with synthetic AWS responses only."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from test_growth_b_mac_snapshot import future_fixture, synthetic_counts

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_mac_snapshot_controller as c


def fixture():
    value = future_fixture()
    source, operation = value['source'], value['op']
    operation['sourceProvenance']['key'] = ('datasets/' + c.mac.DATASET + '-mac-snapshots/' + source['snapshot']['identifier']
        + '/source-' + c.mac.digest(source) + '.json')
    return value


class Aws:
    def __init__(self, value):
        self.value = value
        self.calls = []
        self.objects = {}
        self.event_calls = 0
        self.fail_put = False

    def call(self, *args):
        self.calls.append(args)
        operation = args[:2]
        if operation == ('sts', 'get-caller-identity'):
            return {'Account': c.ACCOUNT}
        if operation == ('rds', 'describe-db-instances'):
            return copy.deepcopy(self.value['target_api'])
        snapshot = self.value['snapshot_observation']
        if operation == ('rds', 'describe-db-snapshots'):
            return {'DBSnapshots': [copy.deepcopy(snapshot['snapshot'])]}
        if operation == ('rds', 'list-tags-for-resource'):
            return {'TagList': copy.deepcopy(snapshot['tags'])}
        if operation == ('rds', 'describe-db-snapshot-attributes'):
            return {'DBSnapshotAttributesResult': {'DBSnapshotAttributes': copy.deepcopy(snapshot['attributes'])}}
        if operation == ('cloudtrail', 'lookup-events'):
            self.event_calls += 1
            return {'Events': [] if self.event_calls == 1 else [{'CloudTrailEvent': self.value['event']['rawUtf8']}]}
        if operation == ('s3api', 'put-object'):
            if self.fail_put:
                raise RuntimeError('synthetic uncertain write')
            key = args[args.index('--key')+1]
            if key in self.objects:
                raise AssertionError('An immutable object was written twice')
            version = 'synthetic-object-' + str(len(self.objects)+1)
            self.objects[key] = (version, Path(args[args.index('--body')+1]).read_bytes())
            return {'VersionId': version}
        if operation == ('s3api', 'get-object'):
            version, raw = self.objects[args[args.index('--key')+1]]
            if version != args[args.index('--version-id')+1]:
                raise AssertionError('Wrong object version')
            Path(args[-1]).write_bytes(raw)
            return {'VersionId': version}
        raise AssertionError('Unexpected AWS action: ' + str(operation))


class MacSnapshotController(unittest.TestCase):
    def test_available_is_published_before_cloudtrail_and_polling_does_not_inflate_timer(self):
        value = fixture(); aws = Aws(value); sleeps = []
        with tempfile.TemporaryDirectory() as root:
            result = c.capture_restore(aws, value['source'], value['op'], value['retirement'], root, lambda: None,
                clock=lambda: value['observed_at'], monotonic=lambda: 1, sleep=sleeps.append)
            restored = c.read(Path(root) / 'restore.json')
            self.assertEqual(value['observed_at'], restored['availableObservedAtEpoch'])
            self.assertEqual(595, result['requestToAvailableSeconds'])
            self.assertEqual([15], sleeps)
            self.assertEqual(('rds', 'describe-db-instances'), aws.calls[0][:2])
            available_put = next(i for i, args in enumerate(aws.calls) if args[:2] == ('s3api', 'put-object'))
            cloudtrail = next(i for i, args in enumerate(aws.calls) if args[:2] == ('cloudtrail', 'lookup-events'))
            self.assertLess(available_put, cloudtrail)
            self.assertEqual(result['availableReference']['key'], c.prefix(value['op'])+'available.json')
            self.assertEqual(result['restoreReference']['key'], c.prefix(value['op'])+'restore.json')
            self.assertFalse(restored['sqlReplayed']); self.assertFalse(restored['fullDatasetValidated'])

    def test_unavailable_or_changed_rds_cannot_publish_completion(self):
        for field, replacement in [('DBInstanceStatus', 'starting'), ('DbiResourceId', 'db-OLD'), ('PubliclyAccessible', True)]:
            value = fixture()
            if field == 'DbiResourceId':
                replacement = value['source']['source']['rds']['resourceId']
            value['target_api']['DBInstances'][0][field] = replacement
            aws = Aws(value)
            with tempfile.TemporaryDirectory() as root, self.assertRaises(ValueError):
                c.capture_restore(aws, value['source'], value['op'], value['retirement'], root, lambda: None,
                    clock=lambda: value['observed_at'])
            self.assertFalse(aws.objects)

    def test_uncertain_available_publication_stops_without_retry_or_restore(self):
        value = fixture(); aws = Aws(value); aws.fail_put = True
        with tempfile.TemporaryDirectory() as root, self.assertRaises(RuntimeError):
            c.capture_restore(aws, value['source'], value['op'], value['retirement'], root, lambda: None,
                clock=lambda: value['observed_at'])
        self.assertEqual(1, sum(args[:2] == ('s3api', 'put-object') for args in aws.calls))
        self.assertFalse(any(args[:2] == ('cloudtrail', 'lookup-events') for args in aws.calls))

    def test_admission_requires_no_rds_and_real_clean_empty_state_binding(self):
        value = fixture(); aws = Aws(value)
        with tempfile.TemporaryDirectory() as root, patch.object(c.time, 'time', return_value=value['observed_at']):
            with self.assertRaises(ValueError):
                c.admit(aws, value['source'], value['op'], value['retirement'], root, lambda: None)
        value['target_api']['DBInstances'] = []; aws = Aws(value)
        with tempfile.TemporaryDirectory() as root, patch.object(c.time, 'time', return_value=value['observed_at']):
            self.assertEqual('SOURCE_ABSENT_TARGET_ABSENT', c.admit(aws, value['source'], value['op'], value['retirement'], root, lambda: None)['state'])
        value['op']['emptyState']['sha256'] = 'f'*64
        with self.assertRaises(ValueError):
            c.validate_retirement(value['op'], value['retirement'])

    def test_event_is_actual_closed_projection_and_foreign_requests_are_ignored(self):
        value = fixture(); event = json.loads(value['event']['rawUtf8'])
        event['userIdentity'] = {'sessionContext': {'private': 'not-for-publication'}}
        event['requestParameters']['masterUserPassword'] = 'synthetic-not-public'
        target = c.mac.check_target_rds(value['target_api'], value['op'], value['source'])
        result = c.public_event(json.dumps(event), value['source'], value['op'], target, value['observed_at'])
        self.assertNotIn('userIdentity', result)
        self.assertEqual({'dBInstanceIdentifier', 'dBSnapshotIdentifier'}, set(result['requestParameters']))
        event['requestParameters']['dBInstanceIdentifier'] = 'foreign-target'
        self.assertIsNone(c.public_event(json.dumps(event), value['source'], value['op'], target, value['observed_at']))

    def test_plan_admits_inherited_snapshot_storage_and_rejects_wrong_source_or_import_host(self):
        value = fixture()
        after = {'instance_class': 'db.t3.small', 'engine_version': '8.4.11', 'multi_az': False,
            'storage_encrypted': True, 'publicly_accessible': False, 'tags': {}, 'allocated_storage': None,
            'storage_type': None, 'snapshot_identifier': value['source']['snapshot']['identifier']}
        plan = {'resource_changes': [{'type': 'aws_db_instance', 'change': {'actions': ['create'], 'before': None, 'after': after}}]}
        self.assertEqual('MAC_SNAPSHOT_PLAN_VERIFIED', c.validate_plan(plan, value['source'])['state'])
        after['snapshot_identifier'] = 'airbob-dataset-b-other'
        with self.assertRaises(ValueError): c.validate_plan(plan, value['source'])
        after['snapshot_identifier'] = value['source']['snapshot']['identifier']
        plan['resource_changes'].append({'type': 'aws_instance', 'change': {'before': None, 'after': {'tags': {'Service': 'debezium'}}}})
        with self.assertRaises(ValueError): c.validate_plan(plan, value['source'])

    def test_service_rechecks_all_four_immutable_inputs_and_new_operator(self):
        value = fixture(); restored = c.mac.validate_restore(**value)
        counts = synthetic_counts(value['source'], restored)
        target = c.mac.make_target_receipt(value['source'], restored, counts)
        prep = {'sourceMode': c.mac.MODE, 'rdsCaBundle': c.mac.child(value['source']['children']['serviceManifest'])['preparation']['rdsCaBundle']}
        documents = [('sourceProvenance', 'source.json', value['source']), ('restoreReceipt', 'restore.json', restored),
                     ('countsDdlReceipt', 'counts.json', counts), ('receipt', 'target.json', target)]
        with tempfile.TemporaryDirectory() as root:
            for field, filename, document in documents:
                raw = c.mac.encoded(document); (Path(root)/filename).write_bytes(raw)
                key = value['op']['sourceProvenance']['key'] if field == 'sourceProvenance' else c.prefix(value['op'])+filename
                version = value['op']['sourceProvenance']['versionId'] if field == 'sourceProvenance' else 'synthetic-v1'
                prep[field] = {'key': key, 'versionId': version, 'sha256': c.mac.sha(raw), 'bytes': len(raw)}
            manifest = {'datasetId': c.mac.DATASET, 'runId': target['runId'], 'preparation': prep,
                'rds': {key: target['target'][key] for key in ('identifier', 'resourceId', 'serverUuid')}}
            ref = prep['sourceProvenance']
            original = {'globalBSnapshotRestoreOnly': True, 'globalBSnapshotSourceMode': c.mac.MODE,
                'globalBSnapshotProvenance': {'key': ref['key'], 'version_id': ref['versionId'], 'sha256': ref['sha256'], 'bytes': ref['bytes']},
                'globalBMacSnapshotOperation': restored['operation'], 'runId': target['runId'], 'fencingToken': target['resourceFence'],
                'expiresAt': str(target['window']['expiresAt']), 'approvedExecutionDeadlineEpoch': target['window']['approvedDeadlineEpoch'],
                'rdsInstanceClass': 'db.t3.small'}
            self.assertEqual('MAC_SNAPSHOT_SERVICE_SOURCE_VERIFIED', c.validate_service_files(manifest, original, root)['state'])
            original['fencingToken'] += 1
            with self.assertRaises(ValueError): c.validate_service_files(manifest, original, root)
            original['fencingToken'] -= 1
            (Path(root)/'counts.json').write_text('{}\n')
            with self.assertRaises(ValueError): c.validate_service_files(manifest, original, root)


class RetainedPowerGate(unittest.TestCase):
    def run_gate(self, output, addresses=''):
        script = (Path(__file__).resolve().parents[1]/'scripts/aws-lab.sh').read_text()
        function = script.split('retain_running_lab_power() {', 1)[1].split('\n}\n', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'output.json').write_text(json.dumps(output)); (root/'addresses.txt').write_text(addresses)
            command = '''set -euo pipefail
fail() { exit 23; }
prepare_lab_backend() { :; }
run_terraform_command() {
  case "$1" in
    'Terraform retained power state') cat "$temp_dir/output.json" ;;
    'Terraform power control presence') cat "$temp_dir/addresses.txt" ;;
    *) exit 24 ;;
  esac
}
retain_running_lab_power() {''' + function + '\n}\nretain_running_lab_power\nprintf "%s" "$lab_power_request"\n'
            return subprocess.run(['bash', '-c', command], env={**os.environ, 'temp_dir': directory, 'lab_root': directory},
                text=True, capture_output=True, timeout=10)

    def test_paused_and_incomplete_power_state_reject_services(self):
        for phase in ('fenced', 'writers-stopped', 'stopped', 'dependencies-running', 'connect-running', 'app-running'):
            result = self.run_gate({'lab_power': {'value': {'phase': phase, 'request': {'phase': phase}}}})
            self.assertEqual(23, result.returncode)
        self.assertEqual(23, self.run_gate({}, 'aws_ec2_instance_state.power["app"]\n').returncode)

    def test_running_request_is_preserved_and_absent_controls_stay_null(self):
        request = {'phase': 'running', 'operation_id': 'actual-retained-id', 'original_suspended_processes': ['AlarmNotification']}
        result = self.run_gate({'lab_power': {'value': {'phase': 'running', 'request': request}}})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(request, json.loads(result.stdout))
        self.assertEqual('null', self.run_gate({}, 'terraform_data.run_identity\n').stdout)


class MacSnapshotOperator(unittest.TestCase):
    def test_initial_mac_restore_observes_before_other_rds_reads_and_needs_no_connect_host(self):
        source = (Path(__file__).resolve().parents[1]/'scripts/aws-lab.sh').read_text()
        start = source.index('    current_stage=services-and-data-bootstrap\n')
        end = source.index('    debezium_instance_id=', start)
        setup = '''set -euo pipefail
global_b_snapshot_restore_only=true
global_b_snapshot_source_mode=mac-snapshot-counts-ddl
global_b_import_from_mac=false
probe_instance_id=i-synthetic
lab_root=/unused
run_id=lab-synthetic-snapshot
fencing_token=201
expires_at=1800020000
fail() { exit 23; }
write_global_b_snapshot_admission() { printf '%s\\n' admission >> "$EVENTS"; }
write_tfvars() { printf '%s\\n' tfvars >> "$EVENTS"; }
apply_lab() { printf '%s\\n' apply >> "$EVENTS"; }
capture_global_b_mac_snapshot_restore() { printf '%s\\n' first-available >> "$EVENTS"; }
run_terraform_command() {
  printf '%s\\n' output >> "$EVENTS"
  printf '%s\\n' '{"services":{}}'
}
verify_retained_rds_class() { printf '%s\\n' later-rds-class-read >> "$EVENTS"; }
write_terraform_output_evidence() { printf '%s\\n' saved >> "$EVENTS"; }
run_supervised_mutation() { exit 24; }
'''
        with tempfile.TemporaryDirectory() as directory:
            events = Path(directory)/'events'
            result = subprocess.run(['bash', '-c', setup+source[start:end]+'\nexit 99\n'],
                env={**os.environ, 'EVENTS': str(events)}, capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['admission', 'tfvars', 'apply', 'first-available', 'output', 'output', 'later-rds-class-read', 'saved'],
                             events.read_text().splitlines())
            self.assertIn('mac_counts_ddl_required=true', result.stdout)
            self.assertNotIn('actual_restored_database_verified=true', result.stdout)

    def test_week_ttl_is_available_only_for_explicit_new_mac_snapshot(self):
        source = (Path(__file__).resolve().parents[1]/'scripts/aws-lab.sh').read_text()
        anchor = source.index('    [[ "$load_generator_enabled" == true || "$load_generator_enabled" == false ]]')
        start = source.index('    if [[ "$global_b_snapshot_restore_only"', anchor)
        end = source.index('    [[ "$mode" != scaling', start)
        body = 'set -euo pipefail\nfail() { exit 23; }\n'+source[start:end]
        for snapshot, mode, hours, expected in ((True, c.mac.MODE, 168, 0), (True, c.mac.MODE, 169, 23),
                (True, c.mac.MODE, 5, 23), (True, 'verified-global-b-snapshot', 25, 23),
                (False, c.mac.MODE, 25, 23), (True, 'verified-global-b-snapshot', 24, 0)):
            with self.subTest(snapshot=snapshot, mode=mode, hours=hours):
                result = subprocess.run(['bash', '-c', body], env={**os.environ, 'global_b_snapshot_restore_only': str(snapshot).lower(),
                    'global_b_snapshot_source_mode': mode, 'ttl_hours': str(hours)}, capture_output=True, text=True, timeout=10)
                self.assertEqual(expected, result.returncode, result.stderr)


if __name__ == '__main__': unittest.main()
