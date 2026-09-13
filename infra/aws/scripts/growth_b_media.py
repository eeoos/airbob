#!/usr/bin/env python3
"""Bounded AWS image decoding and listing-local three-month availability.

Adapted from the reviewed OCI helper (698081e14276ec4967b3f54351568a5bea3a9e3c63872efac394447385f321c2).
The caller owns current application identity, the loopback relay and its lease.
"""
import calendar
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request
from zoneinfo import ZoneInfo
from growth_b_cdc_core import core

DECODER_SHA = '6ff7410605fa04bdfd3ad60a07c2b5ff19f05abc3795abd136708717f8405c73'
BOUNDS = {'korea': (38.7, 124, 33, 132), 'north-america': (55, -130, 24, -60)}


class Rejected(Exception):
    pass


def need(value, code):
    if not value:
        raise Rejected(code)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def json_bytes(raw):
    def pairs(items):
        value = {}
        for key, item in items:
            need(key not in value, 'DUPLICATE_JSON_KEY')
            value[key] = item
        return value
    return json.loads(raw, object_pairs_hook=pairs)


def read(path):
    path = Path(path)
    need(path.is_file() and not path.is_symlink(), 'REGULAR_INPUT_REQUIRED')
    return json_bytes(path.read_bytes())


def plus_months(day, months):
    absolute = day.year * 12 + day.month - 1 + months
    year, month0 = divmod(absolute, 12)
    return dt.date(year, month0 + 1, min(day.day, calendar.monthrange(year, month0 + 1)[1]))


def availability(value, zone, before, after):
    before_day = before.astimezone(ZoneInfo(zone)).date()
    after_day = after.astimezone(ZoneInfo(zone)).date()
    need(before_day == after_day, 'LOCAL_DATE_CHANGED_REPEAT_READ')
    need(isinstance(value, dict) and set(value) == {'booking_window_start_inclusive',
         'booking_window_end_exclusive', 'unavailable_ranges'}, 'AVAILABILITY_FIELDS_DIFFER')
    start = dt.date.fromisoformat(value['booking_window_start_inclusive'])
    end = dt.date.fromisoformat(value['booking_window_end_exclusive'])
    need(start == before_day and end == plus_months(start, 3), 'CURRENT_THREE_MONTH_WINDOW_REQUIRED')
    ranges = value['unavailable_ranges']
    need(isinstance(ranges, list) and len(ranges) <= (end - start).days, 'UNAVAILABLE_RANGES_INVALID')
    previous, unavailable = start, 0
    for item in ranges:
        need(isinstance(item, dict) and set(item) == {'start_date', 'end_date_exclusive'}, 'UNAVAILABLE_RANGE_FIELDS_DIFFER')
        left, right = dt.date.fromisoformat(item['start_date']), dt.date.fromisoformat(item['end_date_exclusive'])
        need(start <= left < right <= end and left >= previous, 'UNAVAILABLE_RANGE_OUTSIDE_WINDOW_OR_OVERLAP')
        unavailable += (right - left).days
        previous = right
    remaining = (end - start).days - unavailable
    need(remaining > 0, 'SAMPLED_LISTING_HAS_NO_RESERVABLE_NIGHT')
    return {'localDate': start.isoformat(), 'timeZone': zone, 'endExclusive': end.isoformat(),
            'windowDays': (end - start).days, 'unavailableNights': unavailable, 'reservableNights': remaining,
            'currentLocalDateVerified': True}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise Rejected('REDIRECT_NOT_ALLOWED')


class Probe:
    def __init__(self, base, decoder, *, guard, deadline):
        parsed = urllib.parse.urlsplit(base)
        need(parsed.scheme == 'http' and parsed.hostname == '127.0.0.1' and parsed.port is not None
             and not parsed.username and not parsed.password and not parsed.path and not parsed.query and not parsed.fragment,
             'CALLER_BOUND_LOOPBACK_REQUIRED')
        self.base, self.decoder = base, decoder
        self.guard, self.deadline = guard, min(deadline, time.monotonic() + 300)
        self.decoder_sha256 = sha(decoder)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.requests = 0

    def get(self, url, *, image=False):
        self.guard()
        self.requests += 1
        need(self.requests <= 24 and time.monotonic() < self.deadline, 'READ_BUDGET_EXHAUSTED')
        limit = 8 * 1024**2 if image else 2 * 1024**2
        req = urllib.request.Request(url, headers={'Accept': 'image/*' if image else 'application/json'})
        with core.warm.wall_clock_limit(min(20, max(.05, self.deadline - time.monotonic()))), self.opener.open(req, timeout=20) as response:
            need(response.status == 200 and response.headers.get('Content-Encoding', 'identity') == 'identity', 'HTTP_STATUS_OR_ENCODING_INVALID')
            mime = response.headers.get_content_type()
            raw = response.read(limit + 1)
        self.guard()
        need(0 < len(raw) <= limit, 'RESPONSE_SIZE_INVALID')
        if image:
            need(mime.startswith('image/'), 'IMAGE_MIME_REQUIRED')
            return raw, mime
        value = json_bytes(raw)
        need(value.get('success') is True, 'API_SUCCESS_REQUIRED')
        return value['data']

    def decode(self, url, hosts):
        need(sha(self.decoder) == self.decoder_sha256, 'IMAGE_DECODER_CHANGED_DURING_EXECUTION')
        parsed = urllib.parse.urlsplit(url)
        need(parsed.scheme == 'https' and parsed.hostname in hosts and parsed.port in (None, 443)
             and not parsed.username and not parsed.password and not parsed.fragment, 'SEALED_PUBLIC_IMAGE_HOST_REQUIRED')
        raw, mime = self.get(url, image=True)
        command = [str(Path(os.environ['JAVA_HOME']) / 'bin/java'), '-Xmx256m', '-Djava.awt.headless=true', str(self.decoder)]
        self.guard()
        result = subprocess.run(command, input=raw, capture_output=True, timeout=min(40, max(.05, self.deadline - time.monotonic())))
        self.guard()
        need(result.returncode == 0, 'FULL_IMAGE_DECODE_FAILED')
        decoded = json_bytes(result.stdout)
        need(decoded.get('fullyDecoded') is True, 'FULL_IMAGE_DECODE_REQUIRED')
        return {'urlSha256': hashlib.sha256(url.encode()).hexdigest(), 'host': parsed.hostname,
                'status': 200, 'mimeType': mime, 'bytes': len(raw), 'contentSha256': hashlib.sha256(raw).hexdigest(), **decoded}


def inputs(release, dataset, consumer_sha256, checks_sha256):
    need(sha(release / 'consumer-manifest.json') == consumer_sha256 and sha(release / 'SHA256SUMS.json') == checks_sha256,
         'SEALED_FINAL_INPUTS_CHANGED')
    checks = read(release / 'SHA256SUMS.json')
    consumer = read(release / 'consumer-manifest.json')
    need(consumer.get('datasetId') == dataset and consumer.get('finalScaleSelected') is True
         and consumer.get('datasetScale') == 'selected-global-b-ten-million', 'SEALED_FINAL_INPUTS_CHANGED')
    documents = {}
    for name in ('manifest.json', 'representative-accounts.json', 'image-qualification.json'):
        need(sha(release / name) == checks[name], 'SEALED_METADATA_CHANGED')
        documents[name] = read(release / name)
    need(documents['manifest.json']['datasetId'] == dataset, 'FINAL_DATASET_REQUIRED')
    selected = documents['representative-accounts.json']['accounts']
    need({v['key'] for v in selected} == {'demo', 'host', 'admin'}, 'REPRESENTATIVE_SELECTION_REQUIRED')
    images = documents['image-qualification.json']
    need(images['passed'] is True and all(v.get('passed') is True and v.get('fullyDecoded') is True
         for v in images['observations']), 'SEALED_IMAGE_QUALIFICATION_REQUIRED')
    hosts = {urllib.parse.urlsplit(v['url']).hostname for v in images['observations']}
    return selected, hosts


def verify(release, dataset, consumer_sha256, checks_sha256, base_url, output, *, guard, deadline, probe_factory=Probe):
    release, output = Path(release).resolve(), Path(output).absolute()
    decoder = Path(__file__).with_name('GlobalBImageDecode.java')
    selected, hosts = inputs(release, dataset, consumer_sha256, checks_sha256)
    need(not decoder.is_symlink() and sha(decoder) == DECODER_SHA, 'IMAGE_DECODER_CHANGED')
    need(not output.exists() and output.parent.is_dir() and not output.parent.is_symlink()
         and not output.is_relative_to(release), 'NEW_OUTPUT_REQUIRED')
    output.mkdir(mode=0o700)
    report = {'schemaVersion': 1, 'kind': 'global-b-public-media-and-availability', 'datasetId': dataset,
              'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'toolSha256': sha(__file__),
              'decoderSha256': DECODER_SHA, 'consumerManifestSha256': consumer_sha256, 'checksumsSha256': checks_sha256,
              'passed': False, 'listings': [], 'images': [], 'search': [],
              'scope': {'businessWrites': 0, 'rawImagesRetained': False, 'allImagesVerified': False,
                        'fullInventoryVerified': False, 'targetRuntimeBoundByCaller': True}}
    try:
        guard()
        probe = probe_factory(base_url, decoder, guard=guard, deadline=deadline)
        targets = {row['ownership']['publishedListings']['sampleIds'][0] for row in selected if row['key'] != 'admin'}
        image_targets = {}
        for name, bounds in BOUNDS.items():
            query = dict(zip(('topLeftLat', 'topLeftLng', 'bottomRightLat', 'bottomRightLng'), bounds))
            query.update(page=0, adultOccupancy=1)
            data = probe.get(probe.base + '/api/v1/search/accommodations?' + urllib.parse.urlencode(query))
            rows = data.get('stay_search_result_listing')
            need(isinstance(rows, list) and 0 < len(rows) <= 18, 'REGIONAL_SEARCH_RESULTS_REQUIRED')
            chosen = next((row for row in rows if (row['address_summary']['country'] == 'South Korea') == (name == 'korea')), None)
            need(chosen is not None and type(chosen.get('id')) is int and chosen['id'] > 0, 'REGIONAL_SEARCH_CATEGORY_REQUIRED')
            targets.add(chosen['id']); image_targets[chosen['id']] = name
            report['search'].append({'region': name, 'returnedCount': len(rows), 'sampleListingId': chosen['id']})
        for listing in sorted(targets):
            detail = probe.get(probe.base + f'/api/v1/accommodations/{listing}')
            need(detail.get('id') == listing and isinstance(detail.get('time_zone_id'), str), 'DETAIL_ID_TIMEZONE_REQUIRED')
            observation = None
            for attempt in range(3):
                before = dt.datetime.now(dt.timezone.utc)
                value = probe.get(probe.base + f'/api/v1/accommodations/{listing}/availability')
                after = dt.datetime.now(dt.timezone.utc)
                try:
                    observation = availability(value, detail['time_zone_id'], before, after)
                    break
                except Rejected as error:
                    if str(error) != 'LOCAL_DATE_CHANGED_REPEAT_READ':
                        raise
            need(observation is not None, 'LOCAL_DATE_DID_NOT_CONVERGE')
            report['listings'].append({'listingId': listing, 'readAttempts': attempt + 1, **observation})
            if listing in image_targets:
                images = detail.get('images')
                need(isinstance(images, list) and images and isinstance(images[0].get('image_url'), str), 'DISPLAYED_IMAGE_REQUIRED')
                report['images'].append({'listingId': listing, 'region': image_targets[listing], **probe.decode(images[0]['image_url'], hosts)})
        need(len(report['images']) == 2 and len(report['listings']) >= 2, 'BOUNDED_REGIONAL_SAMPLES_REQUIRED')
        guard()
        report.update(passed=True, imagesSampled=True, reservableDatesPassed=True, requestCount=probe.requests)
    except Exception as error:
        report.update(failureCode=str(error) if isinstance(error, Rejected) else type(error).__name__)
    report.update(state='PUBLIC_MEDIA_AVAILABILITY_VERIFIED' if report['passed'] else 'PUBLIC_MEDIA_AVAILABILITY_FAILED',
                  completedAt=dt.datetime.now(dt.timezone.utc).isoformat())
    target = output / 'media-availability.json'
    core.write_new(target, core.encoded(report))
    return report, target, sha(target)
