"""Current IANA-calendar preparation shared by OCI and RDS B consumers.

Uses the authenticated inventory.horizon contract and the caller's canonical app
bootstrap. Only proven forward local-date changes permit bounded missing-day
reseeding. These helpers never import SQL, edit owned nights, or select old dates.
"""
import datetime as dt
import json
import os
from pathlib import Path
import secrets
from zoneinfo import ZoneInfo

import growth_b_contract as contract
from growth_b_contract import integer, require


def write(path, value):
    """Atomic private output, including the first byte; never follow a symlink."""
    path = Path(path)
    require(not path.is_symlink(), 'Unsafe report destination')
    temporary = path.with_name(path.name + '.' + secrets.token_hex(6) + '.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


class InventoryHorizonIncomplete(AssertionError):
    def __init__(self, zones):
        super().__init__('Current published inventory horizon incomplete')
        self.zones = sorted(zones)


def inventory_date_vector(db, *, now=None):
    rows = db.rows("SELECT time_zone_id,COUNT(*) n FROM accommodation WHERE status='PUBLISHED' GROUP BY time_zone_id")
    populations = {row['time_zone_id']: row['n'] for row in rows}
    require(rows and len(populations) == len(rows) and all(integer(n, 1) for n in populations.values()),
            'Published timezone population is missing or ambiguous')
    observed = dt.datetime.now(dt.timezone.utc) if now is None else now
    require(observed.tzinfo is not None, 'Inventory observations require an aware current instant')
    return {'observedAt': observed.astimezone(dt.timezone.utc).isoformat(),
            'publishedListingsByZone': dict(sorted(populations.items())),
            'dates': {zone: observed.astimezone(ZoneInfo(zone)).date().isoformat() for zone in sorted(populations)}}


def inventory_date_transitions(before, after):
    require(before['publishedListingsByZone'] == after['publishedListingsByZone']
            and set(before['dates']) == set(after['dates']), 'Published timezone population changed during preparation')
    changed = []
    for zone in before['dates']:
        old, new = dt.date.fromisoformat(before['dates'][zone]), dt.date.fromisoformat(after['dates'][zone])
        require(new >= old, 'A target local date moved backwards during inventory preparation')
        if new != old:
            changed.append({'zone': zone, 'before': old.isoformat(), 'after': new.isoformat()})
    return changed


def inventory_zone_range_complete(db, zone, population, start, end):
    require(start < end, 'Inventory verification range is empty')
    rows = db.rows("SELECT a.id,COUNT(d.stay_date) n FROM accommodation a LEFT JOIN accommodation_inventory_day d "
        "ON d.accommodation_id=a.id AND d.stay_date>=" + db.literal(start) + " AND d.stay_date<" + db.literal(end) +
        " WHERE a.status='PUBLISHED' AND a.time_zone_id=" + db.literal(zone) + ' GROUP BY a.id')
    return len(rows) == population and all(row['n'] == (end - start).days for row in rows)


def inventory_horizon_at_vector(db, inventory, vector):
    details, missing = [], []
    for zone, population in vector['publishedListingsByZone'].items():
        start, end = inventory.horizon(dt.date.fromisoformat(vector['dates'][zone]))
        if not inventory_zone_range_complete(db, zone, population, start, end):
            missing.append(zone)
        details.append({'zone': zone, 'startInclusive': start.isoformat(), 'endExclusive': end.isoformat(),
                        'publishedListings': population, 'expectedRows': population * (end - start).days})
    if missing:
        raise InventoryHorizonIncomplete(missing)
    return {'publishedListings': sum(vector['publishedListingsByZone'].values()), 'everyHorizonContiguous': True,
            'ranges': details, 'currentHorizonRows': sum(row['expectedRows'] for row in details),
            'pastOccupiedRows': db.scalar("SELECT COUNT(*) FROM accommodation_inventory_day WHERE state='OCCUPIED' AND stay_date<CURRENT_DATE")}


def verify_inventory_epoch(db, inventory, before_seed, after_seed, observation):
    """Retry only a proven calendar transition; SQL and real missing-day failures escape."""
    before = inventory_date_vector(db)
    inventory_date_transitions(before_seed, after_seed)
    inventory_date_transitions(after_seed, before)
    observation.update(beforeSeed=before_seed, afterSeed=after_seed, verificationStarted=before)
    missing = None
    try:
        horizon = inventory_horizon_at_vector(db, inventory, before)
    except InventoryHorizonIncomplete as error:
        missing = error
    after = inventory_date_vector(db)
    during_check = inventory_date_transitions(before, after)
    since_seed = inventory_date_transitions(before_seed, after)
    observation.update(verificationFinished=after, dateTransitions=since_seed,
                       changedDuringVerification=during_check, missingZones=[] if missing is None else missing.zones)
    if missing is not None:
        changed_zones = {row['zone'] for row in since_seed}
        # A rollover elsewhere cannot excuse missing days in an unchanged timezone.
        if not set(missing.zones) <= changed_zones:
            raise missing
        for zone in missing.zones:
            old_start, old_end = inventory.horizon(dt.date.fromisoformat(before_seed['dates'][zone]))
            new_start, new_end = inventory.horizon(dt.date.fromisoformat(after['dates'][zone]))
            # Every batch's real local date lay between these observations. The
            # common interval was required throughout, so a hole here is never a midnight retry.
            if not inventory_zone_range_complete(db, zone, before_seed['publishedListingsByZone'][zone],
                                                  max(old_start, new_start), min(old_end, new_end)):
                raise missing
        observation['unchangedRangeVerified'] = True
        overlap_finish = inventory_date_vector(db)
        inventory_date_transitions(after, overlap_finish)
        observation['rolloverClassificationFinished'] = overlap_finish
    if missing is not None or during_check:
        require(since_seed, 'Inventory retry has no proven local-date transition')
        observation['state'] = 'CURRENT_DATE_RESEED_REQUIRED'
        return None
    observation['state'] = 'CURRENT_HORIZON_VERIFIED'
    horizon.update(checkedAt=after['observedAt'], localDateVector=after['dates'])
    return horizon


def prepare_inventory_epochs(db, inventory, owners, app_factory, qualify, finalize, evidence_path, *, max_attempts=3):
    """Converge using actual app bootstrap, with no historical date or missing-day waiver."""
    require(integer(max_attempts, 1) and max_attempts <= 5, 'Inventory seed attempts must be bounded')
    report = {'schemaVersion': 1, 'kind': 'global-b-current-inventory-preparation', 'state': 'STARTED',
              'maximumSeedAttempts': max_attempts, 'seedPolicy': 'canonical app startup inserts only missing FREE days',
              'clockPolicy': 'actual target IANA local dates; never a frozen past date', 'attempts': []}
    try:
        for attempt in range(1, max_attempts + 1):
            before_seed = inventory_date_vector(db)
            record = {'attempt': attempt, 'beforeSeed': before_seed, 'state': 'SEED_STARTED', 'verifications': []}
            report['attempts'].append(record); write(evidence_path, report)
            with app_factory(attempt) as app:
                after_seed = inventory_date_vector(db)
                record['afterSeed'] = after_seed
                require(inventory.owned_fingerprint(db) == owners, 'Bootstrap changed HOLD/OCCUPIED ownership')
                observation = {'phase': 'after-seed'}; record['verifications'].append(observation)
                horizon = verify_inventory_epoch(db, inventory, before_seed, after_seed, observation)
                write(evidence_path, report)
                if horizon is None:
                    record['state'] = 'CURRENT_DATE_RESEED_REQUIRED'; write(evidence_path, report)
                    continue
                result = qualify(app, attempt)
                startup_seconds = app.startup_seconds
            prepared = finalize(attempt)
            require(inventory.owned_fingerprint(db) == owners, 'Prepared inventory ownership changed')
            # Full-row fingerprinting can itself span a midnight. Validate today's
            # complete horizon again before accepting that prepared checkpoint.
            observation = {'phase': 'after-full-fingerprint'}; record['verifications'].append(observation)
            horizon = verify_inventory_epoch(db, inventory, before_seed, after_seed, observation)
            record['preparedFingerprintSemanticSha256'] = contract.canonical_sha(prepared)
            if horizon is None:
                record['state'] = 'CURRENT_DATE_RESEED_REQUIRED'; write(evidence_path, report)
                continue
            record['state'] = 'CURRENT_HORIZON_VERIFIED'
            report.update(state='CURRENT_HORIZON_VERIFIED', seedAttempts=attempt,
                          currentDateVector=horizon['localDateVector'], completedAt=horizon['checkedAt'],
                          ownerSha256BeforeAndAfter=owners)
            write(evidence_path, report)
            return horizon, result, startup_seconds, report
        raise RuntimeError('Current inventory dates kept changing after bounded canonical reseeding')
    except BaseException as error:
        report.update(state='FAILED', errorType=type(error).__name__)
        write(evidence_path, report)
        raise


