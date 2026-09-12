"""Deterministic midnight races; clocks advance, missing days never get waived."""
import calendar
from contextlib import contextmanager
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_inventory as inventory_dates


def horizon(day):
    month = day.month + 3
    year, month = day.year + (month - 1) // 12, (month - 1) % 12 + 1
    return day, dt.date(year, month, min(day.day, calendar.monthrange(year, month)[1])) + dt.timedelta(days=7)


class CalendarDatabase:
    def __init__(self):
        self.calendars = {zone: {} for zone in ('Asia/Seoul', 'Australia/Eucla')}
        self.calendars['Asia/Seoul'][dt.date(2026, 9, 12)] = ('HOLD', 'held-owner')
        self.calendars['Australia/Eucla'][dt.date(2026, 9, 13)] = ('OCCUPIED', 'paid-owner')
        self.range_queries = 0
        self.range_error_at = None
        self.omit = None
        self.seeded = []

    def literal(self, value):
        return "'" + str(value) + "'"

    def rows(self, sql):
        if sql.startswith('SELECT time_zone_id'):
            return [{'time_zone_id': zone, 'n': 1} for zone in self.calendars]
        self.range_queries += 1
        if self.range_error_at == self.range_queries:
            raise OSError('synthetic SQL connection failure')
        zone = re.search(r"a.time_zone_id='([^']+)'", sql)[1]
        start = dt.date.fromisoformat(re.search(r"d.stay_date>='([^']+)'", sql)[1])
        end = dt.date.fromisoformat(re.search(r"d.stay_date<'([^']+)'", sql)[1])
        return [{'id': 1, 'n': sum(start <= day < end for day in self.calendars[zone])}]

    def scalar(self, sql):
        return 0

    def owners(self):
        values = [(zone, str(day), value) for zone, rows in sorted(self.calendars.items())
                  for day, value in sorted(rows.items()) if value[0] != 'FREE']
        return hashlib.sha256(json.dumps(values).encode()).hexdigest()

    def seed(self, dates):
        inserted = []
        for zone, day in dates.items():
            start, end = horizon(dt.date.fromisoformat(day))
            while start < end:
                if (zone, start) != self.omit and start not in self.calendars[zone]:
                    self.calendars[zone][start] = ('FREE', None)
                    inserted.append((zone, start))
                start += dt.timedelta(days=1)
        self.seeded.append(inserted)


class MidnightPreparationTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(); self.addCleanup(directory.cleanup)
        self.output = Path(directory.name) / 'inventory-preparation.json'
        self.db = CalendarDatabase()
        self.inventory = SimpleNamespace(horizon=horizon, owned_fingerprint=lambda db: db.owners())
        self.owners = self.db.owners()
        self.last_vector = None
        self.apps = []
        self.mutate_owner = False
        self.qualify = MagicMock(return_value={'passed': True})
        self.finalize = MagicMock(return_value={'tables': {'inventory': {'rows': 196}}})
        self.old = dt.datetime(2026, 9, 11, 14, 59, 59, tzinfo=dt.timezone.utc)
        self.seoul = dt.datetime(2026, 9, 11, 15, 0, 1, tzinfo=dt.timezone.utc)
        self.eucla = dt.datetime(2026, 9, 11, 15, 15, 1, tzinfo=dt.timezone.utc)
        self.real_vector = inventory_dates.inventory_date_vector

    @contextmanager
    def app_factory(self, attempt):
        self.apps.append(attempt)
        self.db.seed(self.last_vector['dates'])
        if self.mutate_owner:
            self.db.calendars['Asia/Seoul'][dt.date(2026, 9, 12)] = ('FREE', None)
        yield SimpleNamespace(startup_seconds=1)

    def run_at(self, times, *, max_attempts=3):
        ticks = iter(times)
        def vector(db):
            self.last_vector = self.real_vector(db, now=next(ticks))
            return self.last_vector
        with patch.object(inventory_dates, 'inventory_date_vector', side_effect=vector):
            return inventory_dates.prepare_inventory_epochs(self.db, self.inventory, self.owners, self.app_factory,
                self.qualify, self.finalize, self.output, max_attempts=max_attempts)

    def test_actual_korean_midnight_adds_december_18_without_replacing_owned_rows(self):
        # First seed uses Sept 11. By verification Seoul requires Dec 18 as the last included day.
        result = self.run_at([self.old, self.seoul, self.seoul, self.seoul, self.seoul] + [self.seoul] * 6)
        self.assertEqual(self.apps, [1, 2])
        self.assertEqual(self.db.owners(), self.owners)
        self.assertIn(('Asia/Seoul', dt.date(2026, 12, 18)), self.db.seeded[1])
        self.assertEqual(self.db.seeded[1], [('Asia/Seoul', dt.date(2026, 12, 18))])
        self.assertEqual(result[0]['localDateVector'], {'Asia/Seoul': '2026-09-12', 'Australia/Eucla': '2026-09-11'})
        self.assertEqual(result[3]['attempts'][0]['verifications'][0]['missingZones'], ['Asia/Seoul'])
        self.assertTrue(result[3]['attempts'][0]['verifications'][0]['unchangedRangeVerified'])

    def test_a_second_timezone_rollover_during_reverification_seeds_again(self):
        times = [self.old, self.old, self.old, self.seoul]
        times += [self.seoul, self.seoul, self.seoul, self.eucla]
        times += [self.eucla] * 6
        result = self.run_at(times)
        self.assertEqual(self.apps, [1, 2, 3])
        self.assertEqual(self.qualify.call_count, 1)
        self.assertEqual(result[0]['localDateVector'], {'Asia/Seoul': '2026-09-12', 'Australia/Eucla': '2026-09-12'})
        self.assertEqual(self.db.seeded[1], [('Asia/Seoul', dt.date(2026, 12, 18))])
        self.assertEqual(self.db.seeded[2], [('Australia/Eucla', dt.date(2026, 12, 18))])
        self.assertEqual(self.db.owners(), self.owners)

    def test_rollover_during_full_fingerprint_is_rechecked_before_success(self):
        # Initial horizon succeeds; the long full fingerprint crosses Seoul midnight.
        times = [self.old] * 4 + [self.seoul] * 3 + [self.seoul] * 6
        result = self.run_at(times)
        self.assertEqual(self.apps, [1, 2])
        self.assertEqual(self.finalize.call_count, 2)
        first = result[3]['attempts'][0]['verifications'][1]
        self.assertEqual(first['phase'], 'after-full-fingerprint')
        self.assertEqual(first['state'], 'CURRENT_DATE_RESEED_REQUIRED')
        self.assertEqual(result[3]['state'], 'CURRENT_HORIZON_VERIFIED')

    def test_missing_day_without_any_rollover_fails_without_retry(self):
        self.db.omit = ('Asia/Seoul', dt.date(2026, 9, 20))
        with self.assertRaises(inventory_dates.InventoryHorizonIncomplete):
            self.run_at([self.old] * 4)
        self.assertEqual(self.apps, [1]); self.qualify.assert_not_called()
        self.assertEqual(json.loads(self.output.read_text())['state'], 'FAILED')

    def test_a_real_hole_in_the_unchanged_interval_is_not_excused_by_midnight(self):
        self.db.omit = ('Asia/Seoul', dt.date(2026, 9, 20))
        with self.assertRaises(inventory_dates.InventoryHorizonIncomplete):
            self.run_at([self.old, self.seoul, self.seoul, self.seoul])
        self.assertEqual(self.apps, [1]); self.qualify.assert_not_called()

    def test_rollover_in_another_timezone_does_not_excuse_missing_days(self):
        self.db.omit = ('Australia/Eucla', dt.date(2026, 9, 20))
        with self.assertRaises(inventory_dates.InventoryHorizonIncomplete):
            self.run_at([self.old, self.seoul, self.seoul, self.seoul])
        self.assertEqual(self.apps, [1])

    def test_sql_errors_propagate_and_are_never_calendar_retries(self):
        self.db.range_error_at = 1
        with self.assertRaisesRegex(OSError, 'SQL connection failure'):
            self.run_at([self.old, self.seoul, self.seoul])
        self.assertEqual(self.apps, [1]); self.qualify.assert_not_called()

    def test_sql_error_in_rollover_classification_is_not_swallowed(self):
        self.db.range_error_at = 3  # Two complete zone checks, then the guaranteed common interval.
        with self.assertRaisesRegex(OSError, 'SQL connection failure'):
            self.run_at([self.old, self.seoul, self.seoul, self.seoul])
        self.assertEqual(self.apps, [1])

    def test_owned_row_mutation_fails_before_any_login_or_retry(self):
        self.mutate_owner = True
        with self.assertRaisesRegex(ValueError, 'HOLD/OCCUPIED'):
            self.run_at([self.old, self.seoul])
        self.assertEqual(self.apps, [1]); self.qualify.assert_not_called(); self.finalize.assert_not_called()

    def test_continually_changing_dates_exhaust_the_explicit_bound(self):
        times = [self.old] * 3 + [self.seoul]
        times += [self.seoul] * 3 + [self.eucla]
        with self.assertRaisesRegex(RuntimeError, 'bounded canonical reseeding'):
            self.run_at(times, max_attempts=2)
        self.assertEqual(self.apps, [1, 2])
        self.assertEqual(self.db.owners(), self.owners)
        self.assertEqual(json.loads(self.output.read_text())['state'], 'FAILED')

    def test_backward_local_date_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'moved backwards'):
            self.run_at([self.seoul, self.old, self.old])
        self.assertEqual(self.apps, [1])

    def test_population_changes_are_not_calendar_events(self):
        before = self.real_vector(self.db, now=self.old)
        after = self.real_vector(self.db, now=self.seoul)
        after['publishedListingsByZone']['Asia/Seoul'] = 2
        with self.assertRaisesRegex(ValueError, 'population changed'):
            inventory_dates.inventory_date_transitions(before, after)


if __name__ == '__main__':
    unittest.main()
