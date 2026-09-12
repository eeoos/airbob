"""Offline endpoint ownership checks for the opt-in Linux OCI bridge transport."""
import copy
import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_search as search


class BridgeTransportTests(unittest.TestCase):
    def setUp(self):
        self.config = {'url': 'http://172.18.0.2:9200', 'version': '8.18.8', 'container': 'airbob-es',
                       'image': 'sha256:' + 'b' * 64,
                       'localDockerBridge': {'expectedContainerId': 'a' * 64, 'network': 'airbob_default'}}
        self.attachment = {'IPAddress': '172.18.0.2', 'NetworkID': 'c' * 64, 'EndpointID': 'd' * 64}
        self.observed = {'Id': 'a' * 64, 'Image': self.config['image'], 'State': {'Running': True},
                         'HostConfig': {'NetworkMode': 'airbob_default'},
                         'NetworkSettings': {'Ports': {'9200/tcp': None},
                                             'Networks': {'airbob_default': self.attachment}}}
        self.network = {'Id': 'c' * 64, 'Name': 'airbob_default', 'Scope': 'local', 'Driver': 'bridge',
                        'Containers': {'a' * 64: {'EndpointID': 'd' * 64, 'IPv4Address': '172.18.0.2/16'}}}
        self.image = {'Id': self.config['image'], 'Os': 'linux', 'Architecture': 'arm64', 'RepoDigests': []}
        self.socket = 'unix:///var/run/docker.sock'
        self.platform = self.start(patch.object(search.sys, 'platform', 'linux'))
        self.start(patch.dict(os.environ, {}, clear=True))
        self.socket_exists = self.start(patch.object(search.Path, 'is_socket', return_value=True))
        self.execute = self.start(patch.object(search, 'run', side_effect=self.docker))

    def start(self, patcher):
        self.addCleanup(patcher.stop)
        return patcher.start()

    def docker(self, args, **kwargs):
        if args == ['docker', 'context', 'show']: return b'default\n'
        if args == ['docker', 'context', 'inspect', 'default']:
            return json.dumps([{'Endpoints': {'docker': {'Host': self.socket}}}]).encode()
        if args[:3] == ['docker', '--host', self.socket]:
            self.assertFalse({'DOCKER_HOST', 'DOCKER_CONTEXT'} & kwargs['environment'].keys())
            command = args[3:]
            if command == ['info', '--format', '{{json .}}']: value = {'OSType': 'linux'}
            elif command == ['container', 'inspect', self.config['container']]: value = [self.observed]
            elif command == ['network', 'inspect', 'airbob_default']: value = [self.network]
            elif command == ['image', 'inspect', self.observed['Image']]: value = [self.image]
            else: raise AssertionError(command)
        elif args == ['docker', 'inspect', self.config['container']]: value = [self.observed]
        elif args == ['docker', 'image', 'inspect', self.observed['Image']]: value = [self.image]
        else: raise AssertionError(args)
        return json.dumps(value).encode()

    def check(self, config=None):
        config = config or self.config
        return search.local_docker_bridge(config, search.urllib.parse.urlsplit(config['url']), 9200)

    def es_api(self, method, path):
        if path == '/':
            return {'cluster_uuid': 'test-cluster', 'version': {'number': '8.18.8', 'build_flavor': 'default',
                'build_hash': 'e' * 40, 'lucene_version': '9.12.1', 'minimum_wire_compatibility_version': '7.17.0',
                'minimum_index_compatibility_version': '7.0.0'}}
        if path == '/_nodes/plugins':
            return {'nodes': {'one': {'plugins': [{'name': 'analysis-nori', 'version': '8.18.8'}],
                                      'modules': [{'name': 'repository-s3', 'version': '8.18.8'}]}}}
        raise AssertionError(path)

    def test_exact_unpublished_bridge_port_is_accepted_and_keeps_runtime_checks(self):
        with patch.object(search.Elasticsearch, 'api', side_effect=self.es_api):
            identity = search.Elasticsearch(self.config).identity()
        self.assertEqual(identity['imageId'], self.config['image'])
        self.assertEqual(identity['requiredPluginVersions'], {'analysis-nori': '8.18.8', 'repository-s3': '8.18.8'})
        self.assertTrue(any(call.args[0][3:5] == ['network', 'inspect'] for call in self.execute.call_args_list))

    def test_bridge_without_explicit_opt_in_never_contacts_http(self):
        config = dict(self.config); config.pop('localDockerBridge')
        with patch.object(search.Elasticsearch, 'api') as api, self.assertRaisesRegex(ValueError, 'local host container port'):
            search.Elasticsearch(config).identity()
        api.assert_not_called()

    def test_existing_loopback_published_and_host_network_endpoints_still_work(self):
        config = dict(self.config); config.pop('localDockerBridge')
        self.observed['NetworkSettings']['Ports']['9200/tcp'] = [{'HostPort': '19200'}]
        with patch.object(search.Elasticsearch, 'api', side_effect=self.es_api):
            self.assertEqual(search.Elasticsearch(config | {'url': 'http://127.0.0.1:19200'}).identity()['version'], '8.18.8')
            self.observed['HostConfig']['NetworkMode'] = 'host'
            self.assertEqual(search.Elasticsearch(config | {'url': 'http://localhost:9200'}).identity()['version'], '8.18.8')

    def test_other_ip_dns_public_ip_loopback_and_wrong_port_are_rejected(self):
        for endpoint in ('http://172.18.0.9:9200', 'http://es:9200', 'http://8.8.8.8:9200',
                         'http://127.0.0.1:9200', 'http://172.18.0.2:9300'):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError): self.check(self.config | {'url': endpoint})

    def test_missing_or_abbreviated_container_id_is_rejected(self):
        for bridge in (True, {}, {'network': 'airbob_default', 'expectedContainerId': 'a' * 12},
                       self.config['localDockerBridge'] | {'allowAny': True}):
            with self.subTest(bridge=bridge), self.assertRaises(ValueError):
                self.check(self.config | {'localDockerBridge': bridge})

    def test_recreated_stopped_or_wrong_image_container_is_rejected(self):
        original = copy.deepcopy(self.observed)
        for update in ({'Id': 'f' * 64}, {'State': {'Running': False}}, {'Image': 'sha256:' + 'f' * 64}):
            self.observed = original | update
            with self.subTest(update=update), self.assertRaises(ValueError): self.check()

    def test_image_requires_exact_pin_but_accepts_the_observed_repository_digest(self):
        with self.assertRaisesRegex(ValueError, 'image differs'): self.check(self.config | {'image': 'sha256:' + 'f' * 64})
        pin = 'registry.example/airbob/es@sha256:' + 'f' * 64
        self.image['RepoDigests'] = [pin]
        self.assertEqual(self.check(self.config | {'image': pin})[1]['Id'], self.image['Id'])

    def test_network_driver_identity_and_membership_must_all_match(self):
        original = copy.deepcopy(self.network)
        for update in ({'Driver': 'overlay'}, {'Scope': 'swarm'}, {'Id': 'f' * 64}, {'Containers': {}},
                       {'Containers': {'a' * 64: {'EndpointID': 'f' * 64, 'IPv4Address': '172.18.0.2/16'}}},
                       {'Containers': {'a' * 64: {'EndpointID': 'd' * 64, 'IPv4Address': '172.18.0.9/16'}}}):
            self.network = original | update
            with self.subTest(update=update), self.assertRaises(ValueError): self.check()

    def test_host_shared_or_missing_networks_are_rejected(self):
        for mode in ('host', 'none', '', 'container:other'):
            self.observed['HostConfig']['NetworkMode'] = mode
            with self.subTest(mode=mode), self.assertRaises(ValueError): self.check()

    def test_non_linux_host_never_invokes_docker(self):
        with patch.object(search.sys, 'platform', 'darwin'), self.assertRaisesRegex(ValueError, 'Linux host'): self.check()
        self.execute.assert_not_called()

    def test_remote_context_or_host_override_cannot_inspect_a_daemon(self):
        for endpoint in ('ssh://remote', 'tcp://remote:2376', 'unix://remote/var/run/docker.sock'):
            self.socket = endpoint; self.execute.reset_mock()
            with self.subTest(endpoint=endpoint), self.assertRaisesRegex(ValueError, 'local Unix'): self.check()
            self.assertFalse(any('--host' in call.args[0] for call in self.execute.call_args_list))
        self.socket = 'unix:///var/run/docker.sock'
        for endpoint in ('tcp://remote:2376', 'unix:///other.sock'):
            with patch.dict(os.environ, {'DOCKER_HOST': endpoint}), self.assertRaisesRegex(ValueError, 'local Unix'): self.check()

    def test_unavailable_local_socket_is_rejected(self):
        self.socket_exists.return_value = False
        with self.assertRaisesRegex(ValueError, 'local Unix'): self.check()

    def test_local_context_is_fixed_explicitly_and_http_proxies_are_disabled(self):
        opener = MagicMock(); opener.open.return_value = io.BytesIO(b'{"ok":true}')
        with patch.dict(os.environ, {'DOCKER_CONTEXT': 'default', 'HTTP_PROXY': 'http://proxy.invalid'}), \
             patch.object(search.urllib.request, 'build_opener', return_value=opener) as build, \
             patch.object(search.urllib.request, 'urlopen') as default_open:
            self.assertEqual(search.Elasticsearch(self.config).api('GET', '/'), {'ok': True})
        self.assertEqual(build.call_args.args[0].proxies, {})
        self.assertIsInstance(build.call_args.args[1], search.NoBridgeRedirect)
        self.assertEqual(opener.open.call_args.args[0].full_url, self.config['url'] + '/')
        default_open.assert_not_called()

    def test_bridge_http_redirects_fail_before_following_another_endpoint(self):
        handler = search.NoBridgeRedirect()
        with self.assertRaises(search.urllib.error.HTTPError) as raised:
            handler.http_error_302(search.urllib.request.Request(self.config['url']), io.BytesIO(), 302,
                                   'Moved', {'location': 'http://other.invalid/'})
        raised.exception.close()

    def test_mysql_non_tls_requires_the_same_exact_bridge_opt_in(self):
        mysql = self.config | {'jdbcUrl': 'jdbc:mysql://172.18.0.2:3306/airbobdb?useSSL=false&allowPublicKeyRetrieval=true'}
        self.assertEqual(search.mysql_endpoint(mysql).hostname, '172.18.0.2')
        mysql.pop('localDockerBridge')
        with self.assertRaisesRegex(ValueError, 'TLS identity'): search.mysql_endpoint(mysql)

    def test_mysql_socket_factory_or_proxy_cannot_redirect_verified_bridge(self):
        for option in ('socketFactory=custom.Class', 'socksProxyHost=other', 'propertiesTransform=custom.Class', 'password=private-value'):
            mysql = self.config | {'jdbcUrl': 'jdbc:mysql://172.18.0.2:3306/airbobdb?' + option}
            with self.subTest(option=option.partition('=')[0]), self.assertRaises(ValueError): search.mysql_endpoint(mysql)

    def test_mysql_loopback_and_rds_verify_identity_behavior_remain_unchanged(self):
        for endpoint in ('jdbc:mysql://127.0.0.1:3306/airbobdb?useSSL=false',
                         'jdbc:mysql://rds.example:3306/airbobdb?sslMode=VERIFY_IDENTITY'):
            search.mysql_endpoint({'jdbcUrl': endpoint})
        self.execute.assert_not_called()
        for mode in ('DISABLED', 'PREFERRED', 'REQUIRED', 'VERIFY_CA'):
            with self.subTest(mode=mode), self.assertRaisesRegex(ValueError, 'TLS identity'):
                search.mysql_endpoint({'jdbcUrl': 'jdbc:mysql://rds.example:3306/airbobdb?sslMode=' + mode})

    def test_bridge_connection_still_requires_live_mysql_uuid_and_exact_version(self):
        adapter = object.__new__(search.SourceAdapter)
        adapter.config = {'mysql': {'expectedServerUuid': '11111111-1111-1111-1111-111111111111'}}
        adapter.schema = 'airbobdb'
        expected = {'version': '8.4.11', 'serverUuid': adapter.config['mysql']['expectedServerUuid'], 'schema': adapter.schema}
        adapter.probe = lambda mode: expected
        self.assertEqual(adapter.identity(), expected)
        for change in ({'serverUuid': '22222222-2222-2222-2222-222222222222'}, {'version': '8.4.10'}, {'schema': 'other'}):
            adapter.probe = lambda mode, change=change: expected | change
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'Live MySQL identity'): adapter.identity()


if __name__ == '__main__': unittest.main()
