"""Shared isolated-app settings for growth dataset qualification."""
from urllib.parse import parse_qsl, urlencode


APPLICATION_RUNTIME = {'jvmTimeZone': 'UTC', 'jdbcConnectionTimeZone': 'UTC',
                       'forceConnectionTimeZoneToSession': True}


def utc_jdbc_url(url):
    """Match the published application's UTC process and JDBC session contract."""
    base, _, query = url.partition('?')
    items = parse_qsl(query, keep_blank_values=True)
    keys = [key for key, _ in items]
    if len(keys) != len(set(keys)):
        raise ValueError('Duplicate JDBC properties in qualification connection')
    properties = dict(items)
    for key, value in [('connectionTimeZone', 'UTC'), ('forceConnectionTimeZoneToSession', 'true')]:
        if key in properties and properties[key] != value:
            raise ValueError('Qualification requires an explicit UTC JDBC connection')
        properties[key] = value
    return base + '?' + urlencode(properties)


BASE_SETTINGS = {'accommodation.detail-cache.enabled': 'false',
 'accommodation.detail-cache.invalidation.kafka.auto-startup': 'false',
 'accommodation.indexing.bootstrap.enabled': 'false',
 'accommodation.indexing.kafka.auto-startup': 'false',
 'cloud.aws.credentials.access-key': 'dummy',
 'cloud.aws.credentials.secret-key': 'dummy',
 'cloud.aws.region.static': 'ap-northeast-2',
 'cloud.aws.s3.bucket': 'dummy-bucket',
 'cloud.aws.s3.write-enabled': 'false',
 'cloud.cloudfront.domain': 'example.test',
 'google.api.enabled': 'false',
 'google.api.key': 'dummy',
 'logging.level.kr.kro.airbob': 'INFO',
 'logging.level.org.hibernate.SQL': 'OFF',
 'management.health.elasticsearch.enabled': 'false',
 'management.health.redis.enabled': 'false',
 'operator-alert.kafka.auto-startup': 'false',
 'operator-alert.slack.enabled': 'false',
 'payment.toss.enabled': 'false',
 'payment.toss.secret-key': 'dummy',
 'reservation.inventory.retention.enabled': 'false',
 'reservation.inventory.seed.enabled': 'false',
 'reservation.inventory.startup.enabled': 'false',
 'spring.datasource.driver-class-name': 'com.mysql.cj.jdbc.Driver',
 'spring.elasticsearch.uris': 'http://127.0.0.1:1',
 'spring.flyway.baseline-on-migrate': 'false',
 'spring.flyway.enabled': 'true',
 'spring.jpa.hibernate.ddl-auto': 'none',
 'spring.jpa.show-sql': 'false',
 'spring.kafka.admin.auto-create': 'false',
 'spring.kafka.bootstrap-servers': '127.0.0.1:1',
 'spring.kafka.listener.auto-startup': 'false',
 'toss.client-key': 'dummy',
 'toss.secret-key': 'dummy'}
