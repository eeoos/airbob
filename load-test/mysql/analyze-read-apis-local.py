#!/usr/bin/env python3
"""Read-only execution-plan inventory for existing local Airbob read APIs.

Uses the configured MySQL container's secret file internally. No credentials or
query result payloads are exported. Plans use repository-equivalent SQL, not HTTP
traces; latency is EXPLAIN ANALYZE iterator time, never API latency.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
TABLES = ['accommodation', 'address', 'occupancy_policy', 'accommodation_image',
          'accommodation_amenity', 'accommodation_review_summary', 'review', 'review_image',
          'member', 'wishlist', 'wishlist_accommodation', 'coupon', 'member_coupon',
          'coupon_issue_unit', 'coupon_issue_unit_composite', 'daily_revenue_stats',
          'settlement', 'common_code', 'common_code_group', 'payment_transaction', 'reservation']


def mysql(sql):
    result = subprocess.run(['docker', 'exec', '-i', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$(cat "$MYSQL_ROOT_PASSWORD_FILE")" exec mysql -uroot --default-character-set=utf8mb4 --batch --raw --skip-column-names airbobdb'],
        input="SET SESSION MAX_EXECUTION_TIME=15000; SET SESSION TRANSACTION READ ONLY; "
              "SET SESSION time_zone='+00:00';\n" + sql,
        text=True, capture_output=True, timeout=25)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str) + '\n')


def discover(output):
    quoted = ','.join("'" + name + "'" for name in TABLES)
    metadata = {
        'capturedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
        'readOnly': True, 'schema': 'airbobdb', 'scope': 'repository-equivalent SQL',
        'server': mysql("SELECT JSON_OBJECT('version',VERSION(),'bufferPoolBytes',@@innodb_buffer_pool_size,"
                        "'isolation',@@transaction_isolation,'optimizerSwitch',@@optimizer_switch,"
                        "'maxConnections',@@max_connections);"),
        'tables': [json.loads(row) for row in mysql(
            "SELECT JSON_OBJECT('table',TABLE_NAME,'estimatedRows',TABLE_ROWS,'dataBytes',DATA_LENGTH,"
            "'indexBytes',INDEX_LENGTH) FROM information_schema.TABLES WHERE TABLE_SCHEMA='airbobdb' "
            f"AND TABLE_NAME IN ({quoted}) ORDER BY TABLE_NAME;").splitlines()],
        'indexes': [json.loads(row) for row in mysql(
            "SELECT JSON_OBJECT('table',TABLE_NAME,'index',INDEX_NAME,'position',SEQ_IN_INDEX,"
            "'column',COLUMN_NAME,'direction',COLLATION,'unique',NON_UNIQUE=0,'visible',IS_VISIBLE,"
            "'estimatedCardinality',CARDINALITY) FROM information_schema.STATISTICS "
            f"WHERE TABLE_SCHEMA='airbobdb' AND TABLE_NAME IN ({quoted}) "
            "ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX;").splitlines()],
        'histograms': mysql("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMN_STATISTICS "
                            "WHERE SCHEMA_NAME='airbobdb';"),
    }
    metadata['server'] = json.loads(metadata['server'])
    save(output / 'metadata.json', metadata)
    queries = {
        'reviewHot': "SELECT accommodation_id,total_review_count FROM accommodation_review_summary ORDER BY total_review_count DESC LIMIT 3",
        'reviewTypical': "SELECT accommodation_id,total_review_count FROM accommodation_review_summary WHERE total_review_count BETWEEN 5 AND 10 ORDER BY accommodation_id LIMIT 1",
        'wishlistHot': "SELECT id,member_id,accommodation_count FROM wishlist WHERE status='ACTIVE' ORDER BY accommodation_count DESC,id LIMIT 3",
        'wishlistTypical': "SELECT id,member_id,accommodation_count FROM wishlist WHERE status='ACTIVE' AND accommodation_count=5 ORDER BY id LIMIT 1",
        'hostHot': "SELECT member_id,COUNT(*) n FROM accommodation GROUP BY member_id ORDER BY n DESC LIMIT 3",
        'couponOwnerHot': "SELECT member_id,COUNT(*) n FROM member_coupon GROUP BY member_id ORDER BY n DESC LIMIT 3",
        'statsLast': "SELECT stat_date FROM daily_revenue_stats ORDER BY stat_date DESC LIMIT 1",
        'settlementLast': "SELECT settlement_month FROM settlement ORDER BY settlement_month DESC LIMIT 1",
        'couponReadiness': "SELECT issue_strategy,COUNT(*),SUM(stock_prepared_at IS NOT NULL),MIN(issue_start_at),MAX(issue_end_at) FROM coupon GROUP BY issue_strategy",
    }
    manifest = {}
    for name, query in queries.items():
        manifest[name] = {'sql': query, 'rows': [row.split('\t') for row in mysql(query).splitlines()]}
    save(output / 'targets.json', manifest)
    return manifest


def catalogue(manifest):
    hot = int(manifest['reviewHot']['rows'][0][0])
    typical = int(manifest['reviewTypical']['rows'][0][0])
    wish, owner = map(int, manifest['wishlistHot']['rows'][0][:2])
    normal_wish, normal_owner = map(int, manifest['wishlistTypical']['rows'][0][:2])
    host = int(manifest['hostHot']['rows'][0][0])
    coupon_owner = int(manifest['couponOwnerHot']['rows'][0][0])
    month = manifest['settlementLast']['rows'][0][0]
    stat_last = dt.date.fromisoformat(manifest['statsLast']['rows'][0][0])
    start = stat_last.replace(day=1)
    host_aid = int(mysql(f"SELECT id FROM accommodation WHERE member_id={host} AND status<>'DELETED' ORDER BY id LIMIT 1"))
    recent_ids = mysql("SELECT accommodation_id FROM accommodation_review_summary s JOIN accommodation a "
                       "ON a.id=s.accommodation_id WHERE a.status='PUBLISHED' ORDER BY total_review_count DESC,accommodation_id LIMIT 100").splitlines()
    recent = ','.join(recent_ids)
    review_ids = mysql(f"SELECT id FROM review WHERE accommodation_id={hot} AND status='PUBLISHED' ORDER BY created_at DESC,id DESC LIMIT 20").splitlines()
    case = []

    def add(key, api, sql, note=''):
        case.append({'id': key, 'api': api, 'sql': sql, 'note': note})

    review_select = "SELECT r.id,r.rating,r.content,r.created_at,m.id,m.nickname,m.thumbnail_image_url FROM review r JOIN member m ON m.id=r.member_id"
    sorts = {'latest': 'r.created_at DESC,r.id DESC', 'highest': 'r.rating DESC,r.created_at DESC,r.id DESC',
             'lowest': 'r.rating ASC,r.created_at DESC,r.id DESC'}
    for aid, label in [(hot, 'hot'), (typical, 'typical')]:
        for sort, order in sorts.items():
            where = f" WHERE r.accommodation_id={aid} AND r.status='PUBLISHED'"
            add(f'reviews-{label}-{sort}', 'GET /api/v1/accommodations/{id}/reviews',
                review_select + where + ' ORDER BY ' + order + ' LIMIT 21', f'accommodation={aid}; page size=20')
    cursor = mysql(f"SELECT id,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'),rating FROM review WHERE accommodation_id={hot} "
                   "AND status='PUBLISHED' ORDER BY created_at DESC,id DESC LIMIT 1 OFFSET 99").split('\t')
    if len(cursor) == 3:
        rid, created, rating = cursor
        latest = f"(r.created_at<'{created}' OR (r.created_at='{created}' AND r.id<{rid}))"
        add('reviews-hot-latest-cursor', 'GET /api/v1/accommodations/{id}/reviews',
            review_select + f" WHERE r.accommodation_id={hot} AND r.status='PUBLISHED' AND {latest} ORDER BY {sorts['latest']} LIMIT 21",
            'cursor chosen from the 100th existing review; SQL has no OFFSET')
    add('review-images', 'GET /api/v1/accommodations/{id}/reviews',
        f"SELECT ri.* FROM review_image ri WHERE ri.review_id IN ({','.join(review_ids)})", 'batch images of 20 response reviews')

    detail = "SELECT a.id,a.name,a.description,a.type,a.base_price,a.currency,a.check_in_time,a.check_out_time,a.time_zone_id," \
        "d.country,d.state,d.city,d.district,d.latitude,d.longitude,m.id,m.nickname,m.thumbnail_image_url," \
        "p.max_occupancy,p.infant_occupancy,p.pet_occupancy,s.total_review_count,s.average_rating " \
        "FROM accommodation a LEFT JOIN address d ON d.id=a.address_id LEFT JOIN occupancy_policy p ON p.id=a.occupancy_policy_id " \
        "LEFT JOIN member m ON m.id=a.member_id LEFT JOIN accommodation_review_summary s ON s.accommodation_id=a.id"
    add('accommodation-detail', 'GET /api/v1/accommodations/{id}', detail + f" WHERE a.id={hot} AND a.status='PUBLISHED'", 'cache miss')
    add('detail-images', 'accommodation detail / host detail',
        f"SELECT id,image_url FROM accommodation_image WHERE accommodation_id={hot} ORDER BY id")
    add('detail-amenities', 'accommodation detail / host detail',
        f"SELECT amenity_code,count FROM accommodation_amenity WHERE accommodation_id={hot}")
    add('detail-wishlist-exists', 'GET /api/v1/accommodations/{id}',
        f"SELECT wa.id FROM wishlist_accommodation wa JOIN wishlist w ON w.id=wa.wishlist_id WHERE w.member_id={owner} AND wa.accommodation_id={hot} LIMIT 1",
        'logged-in viewer; derived exists query shape')
    add('host-detail', 'GET /api/v1/profile/host/accommodations/{id}',
        "SELECT a.id,a.name,a.description,a.type,a.base_price,a.currency,a.check_in_time,a.check_out_time,a.time_zone_id,"
        "d.country,d.state,d.city,d.district,d.street,d.detail,d.postal_code,p.max_occupancy,p.infant_occupancy,p.pet_occupancy "
        "FROM accommodation a LEFT JOIN address d ON d.id=a.address_id LEFT JOIN occupancy_policy p ON p.id=a.occupancy_policy_id "
        f"WHERE a.id={host_aid} AND a.member_id={host}")
    host_select = "SELECT a.id,a.name,a.thumbnail_url,a.status,a.type,d.country,d.state,d.city,d.district,a.created_at " \
        "FROM accommodation a LEFT JOIN address d ON d.id=a.address_id "
    for status in [None, 'PUBLISHED', 'UNPUBLISHED']:
        condition = '' if status is None else f" AND a.status='{status}'"
        add('host-list-' + (status or 'all').lower(), 'GET /api/v1/profile/host/accommodations',
            host_select + f"WHERE a.member_id={host} AND a.status<>'DELETED'{condition} ORDER BY a.created_at DESC,a.id DESC LIMIT 21")
    hc = mysql(f"SELECT id,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f') FROM accommodation WHERE member_id={host} "
               "AND status<>'DELETED' ORDER BY created_at DESC,id DESC LIMIT 1 OFFSET 999").split('\t')
    if len(hc) == 2:
        aid, stamp = hc
        add('host-list-cursor', 'GET /api/v1/profile/host/accommodations', host_select +
            f"WHERE a.member_id={host} AND a.status<>'DELETED' AND (a.created_at<'{stamp}' OR (a.created_at='{stamp}' AND a.id<{aid})) "
            "ORDER BY a.created_at DESC,a.id DESC LIMIT 21", 'cursor chosen at existing item 1000')

    for wid, mid, label in [(wish, owner, 'hot'), (normal_wish, normal_owner, 'typical')]:
        add(f'wishlist-list-{label}', 'GET /api/v1/members/wishlists',
            "SELECT w.id,w.name,w.created_at,w.accommodation_count,a.thumbnail_url,NULL "
            "FROM wishlist w LEFT JOIN accommodation a ON a.id=w.representative_accommodation_id "
            f"WHERE w.member_id={mid} AND w.status='ACTIVE' ORDER BY w.created_at DESC,w.id DESC LIMIT 21")
        add(f'wishlist-items-{label}', 'GET /api/v1/members/wishlists/accommodations/{id}',
            "SELECT wa.id,wa.memo,a.id,a.name,a.thumbnail_url,d.country,d.state,d.city,d.district,"
            "s.average_rating,s.total_review_count,wa.created_at FROM wishlist_accommodation wa "
            "JOIN accommodation a ON a.id=wa.accommodation_id JOIN address d ON d.id=a.address_id "
            "LEFT JOIN accommodation_review_summary s ON s.accommodation_id=a.id "
            f"WHERE wa.wishlist_id={wid} AND a.status='PUBLISHED' ORDER BY wa.created_at DESC,wa.id DESC LIMIT 21")
    add('wishlist-list-membership', 'GET /api/v1/members/wishlists?accommodationId=...',
        "SELECT w.id,w.name,w.created_at,w.accommodation_count,a.thumbnail_url,wa.id FROM wishlist w "
        "LEFT JOIN accommodation a ON a.id=w.representative_accommodation_id "
        f"LEFT JOIN wishlist_accommodation wa ON wa.wishlist_id=w.id AND wa.accommodation_id={hot} "
        f"WHERE w.member_id={owner} AND w.status='ACTIVE' ORDER BY w.created_at DESC,w.id DESC LIMIT 21")
    add('wishlist-header', 'GET /api/v1/members/wishlists/accommodations/{id}',
        f"SELECT id,member_id,name FROM wishlist WHERE id={wish} AND status='ACTIVE'")
    add('wishlist-membership', 'GET /api/v1/members/wishlists/membership',
        f"SELECT CAST(EXISTS(SELECT 1 FROM wishlist_accommodation wa JOIN wishlist w ON w.id=wa.wishlist_id WHERE w.member_id={owner} AND w.status='ACTIVE' AND wa.accommodation_id={hot}) AS SIGNED),"
        f"CAST(EXISTS(SELECT 1 FROM wishlist w WHERE w.id={wish} AND w.member_id={owner} AND w.status='ACTIVE') AS SIGNED),"
        f"CAST(EXISTS(SELECT 1 FROM wishlist_accommodation wa JOIN wishlist w ON w.id=wa.wishlist_id WHERE w.id={wish} AND w.member_id={owner} AND w.status='ACTIVE' AND wa.accommodation_id={hot}) AS SIGNED)")
    add('recently-viewed-100', 'GET /api/v1/members/recently-viewed',
        "SELECT a.id,a.name,a.thumbnail_url,d.country,d.state,d.city,d.district,s.total_review_count,s.average_rating "
        "FROM accommodation a LEFT JOIN address d ON d.id=a.address_id LEFT JOIN accommodation_review_summary s ON s.accommodation_id=a.id "
        f"WHERE a.id IN ({recent}) AND a.status='PUBLISHED'", '100 existing popular-review accommodations; constructed input, not sampled Redis traffic')
    add('recently-viewed-wishlist', 'GET /api/v1/members/recently-viewed',
        "SELECT wa.accommodation_id FROM wishlist_accommodation wa JOIN wishlist w ON w.id=wa.wishlist_id "
        "JOIN member m ON m.id=w.member_id JOIN accommodation a ON a.id=wa.accommodation_id "
        f"WHERE m.id={owner} AND a.id IN ({recent}) AND a.status='PUBLISHED'")
    add('recently-viewed-wishlist-exists-candidate', 'candidate SQL for recently-viewed membership',
        f"SELECT a.id FROM accommodation a WHERE a.id IN ({recent}) AND a.status='PUBLISHED' AND EXISTS "
        f"(SELECT 1 FROM wishlist w JOIN wishlist_accommodation wa ON wa.wishlist_id=w.id WHERE w.member_id={owner} AND wa.accommodation_id=a.id)",
        'same set semantics; no ACTIVE filter added')

    add('coupon-campaigns-current', 'GET /api/v1/coupons',
        "SELECT c.id,c.name,c.description,c.discount_type,c.discount_value,c.min_payment_price,c.max_discount_amount,"
        "c.issue_start_at,c.issue_end_at,c.usable_from,c.usable_until,c.total_quantity,"
        "(SELECT COUNT(mc.id) FROM member_coupon mc WHERE mc.coupon_id=c.id) "
        "FROM coupon c WHERE c.is_active=true AND c.stock_prepared_at IS NOT NULL AND c.issue_end_at>UTC_TIMESTAMP(6) "
        "ORDER BY c.issue_start_at DESC,c.id DESC", 'local campaign readiness is recorded; do not infer populated performance from an empty result')
    add('member-coupons', 'GET /api/v1/members/me/coupons',
        "SELECT c.id,c.name,c.description,c.discount_type,c.discount_value,c.min_payment_price,c.max_discount_amount,"
        "c.usable_from,c.usable_until,mc.used,c.is_active FROM member_coupon mc JOIN coupon c ON c.id=mc.coupon_id "
        f"WHERE mc.member_id={coupon_owner} ORDER BY mc.created_at DESC,mc.id DESC")
    for days, label in [(0, 'day'), (6, 'week'), ((stat_last-start).days, 'month')]:
        begin = stat_last-dt.timedelta(days=days)
        add('revenue-' + label, 'GET /api/v1/admin/stats/revenue',
            "SELECT stat_date,SUM(gross_amount),SUM(refund_amount),SUM(net_amount),SUM(payment_count),SUM(refund_count) "
            f"FROM daily_revenue_stats WHERE stat_date BETWEEN '{begin}' AND '{stat_last}' GROUP BY stat_date ORDER BY stat_date")
    add('settlement-host-list', 'GET /api/v1/profile/host/settlements',
        f"SELECT * FROM settlement WHERE host_id={host} AND settlement_month BETWEEN '2025-04-01' AND '{month}' ORDER BY settlement_month")
    add('settlement-host-summary', 'GET /api/v1/profile/host/settlements/summary',
        f"SELECT status,COALESCE(SUM(payout_amount),0),COUNT(*) FROM settlement WHERE host_id={host} GROUP BY status")
    for status in [None, 'PENDING', 'PAID']:
        extra = '' if status is None else f" AND status='{status}'"
        add('settlement-admin-' + (status or 'all').lower(), 'GET /api/v1/admin/settlements',
            f"SELECT * FROM settlement WHERE settlement_month='{month}'{extra} ORDER BY host_id", 'existing API has no pagination')
    next_month = (dt.date.fromisoformat(month).replace(day=28)+dt.timedelta(days=4)).replace(day=1)
    end = next_month-dt.timedelta(days=1)
    add('settlement-detail-lines', 'GET /api/v1/profile/host/settlements/{id}',
        "SELECT t.accommodation_id,t.accommodation_name,SUM(t.gross),SUM(t.refund),SUM(t.gross)-SUM(t.refund) FROM ("
        "SELECT a.id accommodation_id,a.name accommodation_name,COALESCE(pt.amount,0) gross,0 refund FROM payment_transaction pt "
        "JOIN reservation r ON r.id=pt.reservation_id JOIN accommodation a ON a.id=r.accommodation_id "
        f"WHERE a.member_id={host} AND pt.transaction_type='CONFIRM' AND DATE(pt.created_at) BETWEEN '{month}' AND '{end}' UNION ALL "
        "SELECT a.id,a.name,0,COALESCE(pt.cancel_amount,0) FROM payment_transaction pt JOIN reservation r ON r.id=pt.reservation_id "
        "JOIN accommodation a ON a.id=r.accommodation_id "
        f"WHERE a.member_id={host} AND pt.transaction_type IN ('CANCEL','PARTIAL_CANCEL') AND DATE(COALESCE(pt.canceled_at,pt.created_at)) BETWEEN '{month}' AND '{end}'"
        ") t GROUP BY t.accommodation_id,t.accommodation_name ORDER BY t.accommodation_id",
        'settlement endpoint includes ledger joins; reservation/payment APIs themselves excluded')
    add('auth-me', 'GET /api/v1/auth/me', f"SELECT id,email,nickname FROM member WHERE id={owner} AND status='ACTIVE'", 'EXPLAIN only, no profile payload exported')
    add('common-code-group', 'public common-code loader', "SELECT * FROM common_code_group WHERE group_code='AMENITY_TYPE'")
    add('common-codes-public', 'GET /api/v1/common-codes/{group}',
        "SELECT * FROM common_code WHERE group_code='AMENITY_TYPE' AND is_active=true ORDER BY sort_order", 'cache miss')
    add('common-codes-admin', 'GET /api/v1/admin/common-codes/{group}',
        "SELECT * FROM common_code WHERE group_code='AMENITY_TYPE' ORDER BY sort_order")
    add('common-groups-admin', 'GET /api/v1/admin/common-code-groups', "SELECT * FROM common_code_group ORDER BY group_code")
    add('admin-auth', 'all admin read endpoints', f"SELECT id FROM member WHERE id={owner} AND status='ACTIVE' AND role='ADMIN' LIMIT 1")
    return case


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--passes', type=int, default=3)
    parser.add_argument('--only', help='Regex of case IDs to rerun; reuses saved catalogue')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if (output / 'catalogue.json').exists():
        cases = json.loads((output / 'catalogue.json').read_text())
    else:
        cases = catalogue(discover(output))
        save(output / 'catalogue.json', cases)
    summaries = []
    for case in cases:
        if args.only and not re.search(args.only, case['id']):
            continue
        result = dict(case)
        path = output / (case['id'] + '.json')
        try:
            result['explainJson'] = json.loads(mysql('EXPLAIN FORMAT=JSON ' + case['sql']))
            result['analyze'] = []
            for repeat in range(args.passes):
                tree = mysql('EXPLAIN ANALYZE FORMAT=TREE ' + case['sql'])
                match = re.search(r'actual time=([\d.eE+-]+)\.\.([\d.eE+-]+) rows=([\d.eE+-]+) loops=(\d+)', tree)
                sample = {'pass': repeat+1, 'tree': tree}
                if match:
                    sample.update(rootFirstRowMs=float(match[1]), rootTotalMs=float(match[2]),
                                  rootRows=float(match[3]), rootLoops=int(match[4]))
                result['analyze'].append(sample)
            result['status'] = 'complete'
        except Exception as error:
            result.update(status='bounded-failure', error=str(error))
        save(path, result)
        summary = {'id': result['id'], 'status': result['status'],
                   'timesMs': [row.get('rootTotalMs') for row in result.get('analyze', [])],
                   'rows': [row.get('rootRows') for row in result.get('analyze', [])]}
        summaries.append(summary)
        print(json.dumps(summary), flush=True)
    save(output / ('summary-' + (args.only or 'all').replace('|','-') + '.json'), summaries)


if __name__ == '__main__':
    main()
