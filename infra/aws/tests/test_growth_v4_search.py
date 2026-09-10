"""The final source cannot silently lose or substitute its native search snapshot."""
import copy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / 'scripts'))
import growth_v4_aws_contract as contract
import growth_v4_search as search


class SearchBindingTest(unittest.TestCase):
    def setUp(self):
        self.envelope = contract.read(Path(__file__).parents[1] / 'lab/tests/fixtures/growth-v4-final-aws-manifest.json')

    def test_final_requires_exact_source_and_native_companion(self):
        contract.validate_manifest(self.envelope, self.envelope['datasetRelease'])
        self.assertEqual(self.envelope['mysql']['expectedTableRows']['reservation'], 2000268)
        for label, mutate in [
            ('disabled', lambda m: m.update(search={'enabled': False})),
            ('substituted snapshot', lambda m: m['search'].update(snapshotRelease=search.RELEASE+'-other')),
            ('unsealed manifest', lambda m: m['search']['artifacts']['manifest.json'].update(sha256='0'*64)),
            ('unversioned seal', lambda m: m['search']['seal'].update(versionId='null')),
            ('wrong seal prefix', lambda m: m['search']['seal'].update(key='elasticsearch/seals/other.json')),
            ('unbounded payload', lambda m: m['search']['artifacts']['snapshot-reference.json'].update(bytes=20000000)),
        ]:
            with self.subTest(label=label), self.assertRaises(ValueError):
                changed = copy.deepcopy(self.envelope)
                mutate(changed)
                contract.validate_manifest(changed, changed['datasetRelease'])

    def test_existing_search_target_is_never_deleted(self):
        with patch.object(search, 'api', return_value={'existing': {}}) as api:
            with self.assertRaisesRegex(ValueError, 'never deleted implicitly'):
                search.absent('/_alias/accommodations')
            api.assert_called_once_with('GET', '/_alias/accommodations')

    def test_all_document_fields_and_mapping_are_hashed(self):
        document = {'_id': 'uid-1', '_source': {'accommodationId': 1, 'name': '한글', 'description': 'first'}}
        def run(description, mapping_type):
            changed = copy.deepcopy(document)
            changed['_source']['description'] = description
            replies = [{'test': {'mappings': {'properties': {'name': {'type': mapping_type}}}}},
                {'_scroll_id': 'scroll', '_shards': {'failed': 0}, 'hits': {'total': {'value': 1, 'relation': 'eq'}, 'hits': [changed]}},
                {'_scroll_id': 'scroll', '_shards': {'failed': 0}, 'hits': {'hits': []}}, {}]
            with patch.object(search, 'api', side_effect=replies), patch.dict(search.FINGERPRINT, documents=1):
                return search.fingerprint('test')[0]
        before = run('first', 'text')
        changed_text = run('second', 'text')
        changed_mapping = run('first', 'keyword')
        self.assertEqual(before['documents'], changed_text['documents'])
        self.assertEqual(before['identityPairsSha256'], changed_text['identityPairsSha256'])
        self.assertNotEqual(before['contentSha256'], changed_text['contentSha256'])
        self.assertNotEqual(before['mappingSha256'], changed_mapping['mappingSha256'])


if __name__ == '__main__':
    unittest.main()
