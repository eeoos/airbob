"""RDS-compatible, persistent LOCK TABLES and separately journaled DDL reset.

Only the exact two API changes are removed. Binlog remains ON. No SUPER, global
read_only, sql_log_bin=0, table truncation, or transaction-start after table locks.
"""
from __future__ import annotations

import contextlib
import os
import re
import selectors
import signal
import subprocess
import time
import uuid

from growth_b_cdc_core import core, need, Failed
from growth_b_contract import TABLES


class PersistentMysql:
    """One bounded client connection, with delimiter-framed private output.

    The production caller constructs the argv from its verified TLS defaults.
    The injectable argv/factory also permits an owned network-none local test.
    stderr is never inherited, exceptions never contain SQL or server output.
    """
    def __init__(self, argv, *, guard=lambda: None, timeout=30, process_factory=subprocess.Popen, clock=time.monotonic):
        self.guard, self.timeout, self.clock = guard, timeout, clock
        self.buffer, self.closed = b'', False
        self.process = process_factory(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                       start_new_session=True, bufsize=0)

    def query(self, sql):
        need(not self.closed and isinstance(sql, str) and len(sql.encode()) <= core.MAX_BYTES, 'MYSQL_SESSION_OR_QUERY_INVALID')
        self.guard()
        need(self.process.poll() is None, 'MYSQL_LOCK_SESSION_LOST')
        marker = '__AIRBOB_CDC_' + uuid.uuid4().hex + '__'
        deadline = self.clock() + self.timeout
        try:
            self.process.stdin.write((sql + '\nSELECT \'' + marker + '\';\n').encode())
            self.process.stdin.flush()
            rows, size = [], 0
            with selectors.DefaultSelector() as selector:
                selector.register(self.process.stdout, selectors.EVENT_READ)
                while self.clock() < deadline:
                    while b'\n' in self.buffer:
                        line, self.buffer = self.buffer.split(b'\n', 1)
                        if line == marker.encode():
                            return b'\n'.join(rows) + (b'\n' if rows else b'')
                        rows.append(line); size += len(line) + 1
                        need(size <= core.MAX_BYTES, 'MYSQL_PRIVATE_RESULT_BUDGET')
                    self.guard()
                    ready = selector.select(min(1, max(0, deadline - self.clock())))
                    if not ready:
                        need(self.process.poll() is None, 'MYSQL_STATEMENT_FAILED')
                        continue
                    chunk = os.read(self.process.stdout.fileno(), 65536)
                    need(bool(chunk), 'MYSQL_STATEMENT_FAILED')
                    self.buffer += chunk
                    need(size + len(self.buffer) <= core.MAX_BYTES + 128, 'MYSQL_PRIVATE_RESULT_BUDGET')
            raise Failed('MYSQL_SESSION_DEADLINE_REACHED')
        except BaseException:
            # The connection must die on any failed assertion or ambiguous read.
            # --skip-reconnect and no --force make a partial transaction rollback.
            self.close()
            raise

    def close(self):
        if self.closed: return
        self.closed = True
        if self.process.poll() is None:
            with contextlib.suppress(ProcessLookupError): os.killpg(self.process.pid, signal.SIGTERM)
            try: self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                with contextlib.suppress(ProcessLookupError): os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait(timeout=3)
        for stream in (self.process.stdin, self.process.stdout):
            if stream is not None: stream.close()

    def __enter__(self): return self
    def __exit__(self, *_): self.close()


def session_setup():
    return ("SET SESSION time_zone='+00:00'; SET SESSION information_schema_stats_expiry=0; "
            'SET SESSION max_execution_time=5000; SET SESSION innodb_lock_wait_timeout=5; '
            'SET SESSION lock_wait_timeout=5; SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ; '
            'SET autocommit=0;')


def lock_statement():
    return 'LOCK TABLES ' + ','.join('`' + name + '` ' + ('WRITE' if name in core.COLUMNS else 'READ') for name in sorted(TABLES)) + ';'


def session_identity_sql(server_uuid, *, exclusive):
    need(re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', server_uuid), 'RESET_UUID_REQUIRED')
    condition = ("@@server_uuid='" + server_uuid + "' AND @@version LIKE '8.4.11%' AND @@global.log_bin=1 "
        "AND @@session.sql_log_bin=1 AND @@global.binlog_format='ROW' AND @@autocommit=0 "
        'AND @@foreign_key_checks=1 AND @@auto_increment_increment=1 AND @@auto_increment_offset=1')
    if exclusive:
        condition += (" AND (SELECT COUNT(*) FROM information_schema.processlist WHERE ID<>CONNECTION_ID() "
                      "AND USER NOT IN ('system user','event_scheduler','rdsadmin'))=0")
    return core.sql_assert(condition)


def owned_rows_sql(baseline, expected, target):
    """DML only; caller must hold and recheck the persistent write-lock session."""
    for snapshot in (baseline, expected):
        for table in core.COLUMNS: core.validate_rows(table, snapshot[table])
    need(core.integer(target, 1) and len(baseline['accommodation']) == len(expected['accommodation']) == 1
         and core.row_id(baseline['accommodation'][0]) == core.row_id(expected['accommodation'][0]) == target,
         'RESET_TARGET_IDENTITY_REQUIRED')
    need(baseline['schemaSha256'] == expected['schemaSha256'] and baseline['accommodation'][0]['name'] == expected['accommodation'][0]['name'], 'API_NAME_RESTORE_REQUIRED')
    old_h = {core.row_id(row): row for row in baseline['accommodation_history']}
    new_h = {core.row_id(row): row for row in expected['accommodation_history']}
    removed = sorted(set(new_h) - set(old_h))
    need(set(old_h) <= set(new_h) and len(removed) == 2 and not baseline['outbox'] and len(expected['outbox']) == 4,
         'EXACT_TWO_PATCH_CLEANUP_REQUIRED')
    sql = []
    for table in core.COLUMNS:
        where = f'id={target}' if table == 'accommodation' else (f'accommodation_id={target}' if table == 'accommodation_history' else '1=1')
        sql.append(core.sql_assert(f'(SELECT COUNT(*) FROM `{table}` WHERE {where})={len(expected[table])}'))
        for row in expected[table]:
            sql.append(core.sql_assert(f'(SELECT COUNT(*) FROM `{table}` WHERE {core.row_predicate(row)})=1'))
    for row in expected['outbox']:
        sql += ['DELETE FROM outbox WHERE ' + core.row_predicate(row) + ';', core.sql_assert('ROW_COUNT()=1')]
    for key in reversed(removed):
        sql += ['DELETE FROM accommodation_history WHERE ' + core.row_predicate(new_h[key]) + ';', core.sql_assert('ROW_COUNT()=1')]
    for key, original in old_h.items():
        if original != new_h[key]:
            need({k for k in original if original[k] != new_h[key][k]} == {'valid_to'}, 'CLEANUP_HISTORY_SCOPE_EXCEEDED')
            sql += ['UPDATE accommodation_history SET valid_to=' + core.literal(original['valid_to']) + ' WHERE ' + core.row_predicate(new_h[key]) + ';', core.sql_assert('ROW_COUNT()=1')]
    old, new = baseline['accommodation'][0], expected['accommodation'][0]
    need({k for k in old if old[k] != new[k]} <= {'updated_at', 'updated_by'}, 'CLEANUP_ACCOMMODATION_SCOPE_EXCEEDED')
    sql += ['UPDATE accommodation SET updated_at=' + core.literal(old['updated_at']) + ',updated_by=' + core.literal(old['updated_by']) + ' WHERE ' + core.row_predicate(new) + ';', core.sql_assert('ROW_COUNT()=1')]
    for table in core.COLUMNS:
        for row in baseline[table]:
            sql.append(core.sql_assert(f'(SELECT COUNT(*) FROM `{table}` WHERE {core.row_predicate(row)})=1'))
    sql += [core.sql_assert('(SELECT COUNT(*) FROM outbox)=0'), 'COMMIT;']
    return '\n'.join(sql)


class MysqlStore:
    def __init__(self, session_factory, journal, target, server_uuid, *, fence=lambda: None):
        self.factory, self.journal, self.target, self.uuid, self.fence = session_factory, journal, target, server_uuid, fence
        self.active = None
        self.session_id = None
        self.schema_sha = None

    def sql(self, statement):
        if self.active is not None: return self.active.query(statement)
        with self.factory() as session:
            session.query(session_setup())
            return session.query(statement)

    schema = core.DockerAdapter.schema

    def snapshot(self):
        schema = self.schema()
        own = self.active is None
        session = self.factory() if own else self.active
        try:
            if own:
                session.query(session_setup()); session.query('START TRANSACTION WITH CONSISTENT SNAPSHOT;')
            result = {table: [] for table in core.COLUMNS}
            for table, columns in core.COLUMNS.items():
                obj = 'JSON_OBJECT(' + ','.join("'" + k + "',HEX(CAST(`" + k + '` AS BINARY))' for k in columns) + ')'
                where = f'id={self.target}' if table == 'accommodation' else (f'accommodation_id={self.target}' if table == 'accommodation_history' else '1=1')
                limit = 2 if table == 'accommodation' else (core.MAX_HISTORY + 1 if table == 'accommodation_history' else 9)
                result[table] = [core.parse(line) for line in session.query(f'SELECT {obj} FROM `{table}` WHERE {where} ORDER BY id LIMIT {limit};').splitlines()]
            # Each locked table occurs only once per statement (LOCK TABLES alias rules).
            counters = {}
            for table in core.COLUMNS:
                counters[table] = int(session.query("SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema='airbobdb' AND table_name='" + table + "';").strip())
            result['meta'] = {'autoIncrement': counters,
                'historyMaxId': int(session.query('SELECT COALESCE(MAX(id),0) FROM accommodation_history;').strip()),
                'historyCount': int(session.query(f'SELECT COUNT(*) FROM accommodation_history WHERE accommodation_id={self.target};').strip()),
                'outboxCount': int(session.query('SELECT COUNT(*) FROM outbox;').strip())}
            need(len(result['accommodation']) <= 1 and result['meta']['historyCount'] == len(result['accommodation_history']) <= core.MAX_HISTORY
                 and result['meta']['outboxCount'] == len(result['outbox']) <= 4, 'BOUNDED_SNAPSHOT_ROW_SET_REQUIRED')
            result['schemaSha256'] = schema
            if own: session.query('COMMIT;')
            return result
        finally:
            if own: session.close()

    def locked_guard(self):
        self.fence()
        need(self.active is not None and self.session_id is not None, 'PERSISTENT_RESET_SESSION_REQUIRED')
        self.active.query(core.sql_assert('CONNECTION_ID()=' + str(self.session_id)))
        self.active.query(session_identity_sql(self.uuid, exclusive=True))

    @contextlib.contextmanager
    def cleanup_window(self):
        need(self.active is None, 'NESTED_RESET_SESSION_FORBIDDEN')
        self.fence()
        session = self.factory(); self.active = session
        self.journal.add('AWS_LOCK_WINDOW_INTENT', {'method': 'persistent-all-business-table-locks', 'binlogKeptEnabled': True})
        try:
            session.query(session_setup())
            session.query(session_identity_sql(self.uuid, exclusive=True))
            session.query(lock_statement())
            self.session_id = int(session.query('SELECT CONNECTION_ID();').strip())
            self.locked_guard()
            self.journal.add('AWS_LOCK_WINDOW_ACQUIRED', {'connectionId': self.session_id, 'tables': sorted(TABLES)})
            yield
            self.locked_guard()
        except BaseException:
            self.journal.add('AWS_LOCK_WINDOW_UNCONFIRMED', {'privateJournalRetained': True, 'externalWritersMustRemainStopped': True})
            raise
        finally:
            session.close(); self.active = None; self.session_id = None
            self.journal.add('AWS_LOCK_WINDOW_RELEASED', {'clientDisconnected': True, 'rowDdlAtomicityClaimed': False,
                'externalWritersMustRemainStopped': True, 'binlogKeptEnabled': True})

    def reset_rows(self, baseline, expected):
        self.locked_guard()
        need(self.snapshot() == expected, 'ROW_CAS_INPUT_CHANGED')
        self.journal.add('AWS_ROWS_COMMIT_INTENT', {'expectedSha256': core.digest(core.encoded(expected)), 'baselineSha256': core.digest(core.encoded(baseline))})
        self.active.query(owned_rows_sql(baseline, expected, self.target))
        self.locked_guard()
        need(core.snapshot_rows_equal(self.snapshot(), baseline), 'ROW_RESET_VERIFICATION_FAILED')
        self.journal.add('AWS_ROWS_COMMIT_CONFIRMED', {'rowsEqual': True})

    def reset_counter(self, table, value):
        need(table in ('outbox', 'accommodation_history') and core.integer(value, 1), 'COUNTER_RESET_SCOPE_INVALID')
        self.locked_guard()
        before = self.journal.last('BASELINE')['snapshot']; after = self.journal.last('STEP_2_OBSERVED')['after']
        current = self.snapshot(); observed = current['meta']['autoIncrement'][table]
        need(core.snapshot_rows_equal(current, before) and value == before['meta']['autoIncrement'][table]
             and observed == after['meta']['autoIncrement'][table], 'COUNTER_CAS_INPUT_CHANGED')
        # CREATE-only intent precedes the implicit-commit DDL. A retry accepts
        # only the original or exactly owned-allocation counter and exact rows.
        self.journal.add('AWS_COUNTER_DDL_INTENT', {'table': table, 'before': observed, 'after': value,
            'baselineRowsSha256': core.digest(core.encoded(before)), 'connectionId': self.session_id})
        self.active.query(f'ALTER TABLE `{table}` AUTO_INCREMENT={value};')
        self.fence()
        # ALTER can release locks. The external ASG/Connect fence stays closed;
        # reacquire all locks and repeat fresh row/identity/counter comparisons.
        self.active.query(lock_statement())
        self.locked_guard()
        current = self.snapshot()
        need(core.snapshot_rows_equal(current, before) and current['meta']['autoIncrement'][table] == value, 'COUNTER_DDL_READBACK_UNCONFIRMED')
        self.journal.add('AWS_COUNTER_DDL_CONFIRMED', {'table': table, 'value': value, 'locksReacquired': True})
