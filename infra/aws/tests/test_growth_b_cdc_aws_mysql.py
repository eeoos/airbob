"""Real MySQL 8.4.11 reset tests in one explicitly owned network-none container.

These use toy rows and restricted grants. They do not qualify an RDS instance,
an API/CDC pipeline, a prepared B dataset, or permission on a remote host.
"""
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_cdc_core as aws_core
import growth_b_cdc_mysql as mysql
from growth_b_contract import TABLES
from test_growth_b_cdc_aws_fixtures import baseline, changed, ORIGINAL, TARGET

core = aws_core.core


@unittest.skipUnless(os.environ.get('AIRBOB_CDC_AWS_TEST_MYSQL_IMAGE'), 'Explicit local exact MySQL image required')
class RdsSessionSqlTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.image = os.environ['AIRBOB_CDC_AWS_TEST_MYSQL_IMAGE']
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', cls.image): raise AssertionError('Exact local image ID required')
        endpoint = os.environ.get('DOCKER_HOST') or json.loads(subprocess.check_output([
            'docker', 'context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}'], stderr=subprocess.DEVNULL, timeout=10))
        if not isinstance(endpoint, str) or not endpoint.startswith('unix:///'):
            raise AssertionError('Tests require a local UNIX Docker daemon')
        cls.docker = ['docker', '--host', endpoint]
        cls.name = 'airbob-cdc-aws-test-' + uuid.uuid4().hex[:12]
        cls.cid = subprocess.check_output(cls.docker + ['run', '-d', '--rm', '--pull=never', '--name', cls.name,
            '--network', 'none', '--memory', '768m', '--cpus', '1', '--pids-limit', '256',
            '--tmpfs', '/var/lib/mysql:rw,nosuid,size=384m', '-e', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes',
            '--label', 'airbob.cdc.aws.test=' + cls.name, cls.image, '--log-bin=mysql-bin', '--server-id=9982'], stderr=subprocess.DEVNULL).decode().strip()
        until = time.monotonic() + 150
        while time.monotonic() < until:
            result = cls.root_sql('SELECT @@version;', require=False)
            if result.returncode == 0 and result.stdout.startswith(b'8.4.11'): break
            time.sleep(1)
        else:
            cls.tearDownClass(); raise AssertionError('Owned MySQL did not start')
        cls.server_uuid = cls.root_sql('SELECT @@server_uuid;').stdout.decode().strip()

    @classmethod
    def root_sql(cls, sql, *, require=True):
        result = subprocess.run(cls.docker + ['exec', '-i', cls.cid, 'timeout', '-s', 'TERM', '-k', '3', '20',
            'mysql', '--no-defaults', '-uroot', '--protocol=TCP', '--host=127.0.0.1', '--batch', '--raw', '--skip-column-names'],
            input=sql.encode(), capture_output=True, timeout=30)
        if require and result.returncode: raise AssertionError('Owned toy fixture SQL failed')
        return result

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, 'cid', None):
            value = json.loads(subprocess.check_output(cls.docker + ['inspect', cls.cid]))[0]
            if value['Image'] != cls.image or value['Config']['Labels'].get('airbob.cdc.aws.test') != cls.name:
                raise AssertionError('Not the container owned by this test')
            subprocess.run(cls.docker + ['stop', '--time', '10', cls.cid], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.journal = core.Journal(Path(self.temp.name).resolve() / 'journal', 'a' * 64, 'b' * 64); self.addCleanup(self.journal.close)
        v1 = (ROOT / 'src/main/resources/db/migration/V1__init.sql').read_text()
        v18 = (ROOT / 'src/main/resources/db/migration/V18__refactor_messaging_orchestration.sql').read_text()
        ddl = [re.search(r'CREATE TABLE ' + table + r' \(.*?\) ENGINE=.*?;', v1, re.S)[0] for table in core.COLUMNS]
        sql = ['DROP DATABASE IF EXISTS airbobdb;', 'CREATE DATABASE airbobdb CHARACTER SET utf8mb4;', 'USE airbobdb;',
            *['CREATE TABLE `' + name + '` (id BIGINT PRIMARY KEY) ENGINE=InnoDB;' for name in sorted(TABLES - set(core.COLUMNS))],
            'INSERT INTO member VALUES(6675);', *ddl, 'ALTER TABLE accommodation ADD time_zone_id VARCHAR(64);',
            'ALTER TABLE accommodation_history ADD time_zone_id VARCHAR(64);', re.search(r'ALTER TABLE outbox.*?;', v18, re.S)[0],
            "CREATE USER IF NOT EXISTS 'cdc_test'@'%' IDENTIFIED BY '';",
            "GRANT SELECT,INSERT,UPDATE,DELETE,ALTER,LOCK TABLES ON airbobdb.* TO 'cdc_test'@'%';",
            "GRANT PROCESS,REPLICATION CLIENT ON *.* TO 'cdc_test'@'%';"]
        for table, rows in ((table, baseline()[table]) for table in core.COLUMNS):
            for row in rows: sql.append(self.insert(table, row))
        unrelated = copy.deepcopy(baseline()['accommodation_history'][0])
        unrelated.update(id=core.hex_text('150'), accommodation_id=core.hex_text('999'))
        sql.append(self.insert('accommodation_history', unrelated))
        for table, value in baseline()['meta']['autoIncrement'].items(): sql.append(f'ALTER TABLE `{table}` AUTO_INCREMENT={value};')
        self.root_sql('\n'.join(sql))
        self.fence_calls = 0; self.fence_open = False
        self.store = mysql.MysqlStore(self.session, self.journal, TARGET, self.server_uuid, fence=self.fence)
        self.before = self.store.snapshot()
        self.after = changed(changed(self.before, 'temporary', 1), ORIGINAL, 2)
        sql = ['USE airbobdb;']
        for table in core.COLUMNS:
            for row in self.after[table]: sql.append(self.insert(table, row, update=True))
        self.root_sql('\n'.join(sql)); self.after = self.store.snapshot()
        self.journal.add('BASELINE', {'snapshot': self.before})
        self.journal.add('STEP_2_OBSERVED', {'after': self.after})

    def fence(self):
        self.fence_calls += 1
        core.need(not self.fence_open, 'FOREIGN_WRITER_FENCE_CHANGED')

    def session(self, seconds=110):
        return mysql.PersistentMysql(self.docker + ['exec', '-i', self.cid, 'timeout', '-s', 'TERM', '-k', '3', str(seconds),
            'mysql', '--no-defaults', '-ucdc_test', '--protocol=TCP', '--host=127.0.0.1', '--default-character-set=utf8mb4',
            '--batch', '--raw', '--skip-column-names', '--skip-reconnect', '--unbuffered', 'airbobdb'], timeout=10)

    @staticmethod
    def insert(table, row, update=False):
        def literal(key, value):
            return "UNHEX('" + value + "')" if table == 'accommodation' and key == 'accommodation_uid' else core.literal(value)
        sql = 'INSERT INTO `' + table + '` (' + ','.join('`' + k + '`' for k in row) + ') VALUES (' + ','.join(literal(k, v) for k, v in row.items()) + ')'
        if update: sql += ' ON DUPLICATE KEY UPDATE ' + ','.join('`' + k + '`=' + literal(k, v) for k, v in row.items() if k != 'id')
        return sql + ';'

    def reset_all(self):
        with self.store.cleanup_window():
            current = self.store.snapshot()
            if current == self.after: self.store.reset_rows(self.before, self.after)
            else: self.assertTrue(core.snapshot_rows_equal(current, self.before))
            for table in ('outbox', 'accommodation_history'):
                value = self.store.snapshot()['meta']['autoIncrement'][table]
                if value == self.before['meta']['autoIncrement'][table]: continue
                self.store.reset_counter(table, self.before['meta']['autoIncrement'][table])
            self.assertEqual(self.before, self.store.snapshot())

    def test_restricted_session_full_cas_binlog_on_and_exact_counters(self):
        self.reset_all()
        self.assertEqual(self.before, self.store.snapshot())
        result = self.root_sql('USE airbobdb; SELECT COUNT(*) FROM outbox; SELECT COUNT(*) FROM accommodation_history; SELECT @@global.log_bin,@@global.read_only;').stdout.decode().splitlines()
        self.assertEqual(['0', '2', '1\t0'], result)
        grants = self.root_sql("SHOW GRANTS FOR 'cdc_test'@'%';").stdout.decode()
        self.assertNotIn('SUPER', grants); self.assertNotIn('SESSION_VARIABLES_ADMIN', grants)
        self.assertEqual(2, sum(e['kind'] == 'AWS_COUNTER_DDL_CONFIRMED' for e in self.journal.entries))

    def test_mid_dml_guard_failure_disconnect_rolls_back_first_delete(self):
        original = mysql.owned_rows_sql
        def fail_second(*args):
            sql = original(*args); found = list(re.finditer(r'DELETE FROM outbox WHERE .*?;', sql))[1]
            return sql[:found.start()] + 'DELETE FROM outbox WHERE id=-1;' + sql[found.end():]
        with patch.object(mysql, 'owned_rows_sql', side_effect=fail_second):
            with self.assertRaisesRegex(core.Failed, 'MYSQL_STATEMENT_FAILED'):
                with self.store.cleanup_window(): self.store.reset_rows(self.before, self.after)
        self.assertEqual(self.after, self.store.snapshot())
        self.assertTrue(self.journal.last('AWS_LOCK_WINDOW_UNCONFIRMED')['externalWritersMustRemainStopped'])

    def test_foreign_row_change_is_rejected_without_deleting_outbox(self):
        self.root_sql(f"USE airbobdb; UPDATE accommodation SET description='UNOWNED_PRIVATE_CHANGE' WHERE id={TARGET};")
        with self.assertRaisesRegex(core.Failed, 'ROW_CAS_INPUT_CHANGED'):
            with self.store.cleanup_window(): self.store.reset_rows(self.before, self.after)
        self.assertEqual(4, self.store.snapshot()['meta']['outboxCount'])

    def test_lost_commit_journal_is_resumable_without_second_delete(self):
        original = self.journal.add
        def lost(kind, data):
            if kind == 'AWS_ROWS_COMMIT_CONFIRMED': raise core.Failed('SIMULATED_COMMIT_RESPONSE_LOST')
            return original(kind, data)
        with patch.object(self.journal, 'add', side_effect=lost):
            with self.assertRaisesRegex(core.Failed, 'SIMULATED_COMMIT_RESPONSE_LOST'):
                with self.store.cleanup_window(): self.store.reset_rows(self.before, self.after)
        self.assertTrue(core.snapshot_rows_equal(self.store.snapshot(), self.before))
        self.reset_all(); self.assertEqual(self.before, self.store.snapshot())

    def test_first_ddl_committed_but_response_lost_is_resumable(self):
        original = self.journal.add
        def lost(kind, data):
            if kind == 'AWS_COUNTER_DDL_CONFIRMED' and data['table'] == 'outbox': raise core.Failed('SIMULATED_DDL_RESPONSE_LOST')
            return original(kind, data)
        with patch.object(self.journal, 'add', side_effect=lost):
            with self.assertRaisesRegex(core.Failed, 'SIMULATED_DDL_RESPONSE_LOST'): self.reset_all()
        current = self.store.snapshot()
        self.assertEqual(self.before['meta']['autoIncrement']['outbox'], current['meta']['autoIncrement']['outbox'])
        self.assertEqual(self.after['meta']['autoIncrement']['accommodation_history'], current['meta']['autoIncrement']['accommodation_history'])
        self.reset_all(); self.assertEqual(self.before, self.store.snapshot())

    def test_lock_blocks_concurrent_writer_and_rejects_live_client_before_cas(self):
        with self.store.cleanup_window():
            with self.session() as other:
                other.query('SET SESSION lock_wait_timeout=1;')
                with self.assertRaisesRegex(core.Failed, 'MYSQL_STATEMENT_FAILED'):
                    other.query(f"UPDATE accommodation SET name='UNOWNED_PRIVATE_CHANGE' WHERE id={TARGET};")
            self.assertEqual(self.after, self.store.snapshot())
        with self.assertRaisesRegex(core.Failed, 'MYSQL_STATEMENT_FAILED'):
            with self.store.cleanup_window():
                with self.session() as other:
                    other.query('SELECT 1;')
                    self.store.reset_rows(self.before, self.after)
        self.assertEqual(self.after, self.store.snapshot())

    def test_external_fence_loss_before_write_preserves_rows_and_closes_session(self):
        with self.assertRaisesRegex(core.Failed, 'FOREIGN_WRITER_FENCE_CHANGED'):
            with self.store.cleanup_window():
                self.fence_open = True; self.store.reset_rows(self.before, self.after)
        self.fence_open = False
        self.assertEqual(self.after, self.store.snapshot()); self.assertIsNone(self.store.active)

    def test_independent_client_deadline_rolls_back_orphanable_uncommitted_work(self):
        with self.session(seconds=2) as session:
            session.query(mysql.session_setup())
            session.query(mysql.lock_statement())
            session.query('DELETE FROM outbox WHERE id=10;')
            time.sleep(3)
            with self.assertRaises(core.Failed): session.query('SELECT 1;')
        self.assertEqual(self.after, self.store.snapshot())


if __name__ == '__main__': unittest.main()
