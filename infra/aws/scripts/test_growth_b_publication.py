"""Failure-path tests for bounded transport; domain validation has separate tests."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import signal
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

pub = load('b_pub_test', 'publish-growth-dataset-b.py')
fetcher = load('b_fetch_test', 'fetch-growth-dataset-b.py')


class FakeAws:
    def __init__(self):
        self.objects, self.parts, self.calls = {}, {}, []
        self.fail_part = False
        self.wrong_version = False
        self.corrupt_range = False
        self.fail_abort = False
        self.terminate_part = False
    def head(self, key):
        return {'VersionId': 'v1'} if key in self.objects else None
    def keys(self, prefix):
        return {k for k in self.objects if k.startswith(prefix)}
    def call(self, service, operation, *args):
        self.calls.append((operation, args))
        def value(flag):return args[args.index(flag)+1]
        if operation == 'get-caller-identity':
            return {'Account':pub.ACCOUNT,'Arn':f'arn:aws:sts::{pub.ACCOUNT}:assumed-role/airbob-dataset-publisher/test'}
        key = value('--key')
        if operation == 'put-object':
            assert value('--if-none-match') == '*'
            if key in self.objects:raise pub.AwsError(operation, 'PreconditionFailed')
            self.objects[key] = Path(value('--body')).read_bytes()
        elif operation == 'create-multipart-upload':
            self.parts = {}
            return {'UploadId':'u1'}
        elif operation == 'upload-part':
            if self.terminate_part:os.kill(os.getpid(), signal.SIGTERM)
            if self.fail_part:raise pub.AwsError(operation, 'InjectedFailure')
            self.parts[int(value('--part-number'))] = Path(value('--body')).read_bytes()
            return {'ETag':'part-'+value('--part-number')}
        elif operation == 'complete-multipart-upload':
            assert value('--if-none-match') == '*'
            self.objects[key] = b''.join(self.parts[n] for n in sorted(self.parts))
        elif operation == 'abort-multipart-upload':
            if self.fail_abort:raise pub.AwsError(operation, 'InjectedAbortFailure')
            self.parts = {}
        elif operation == 'get-object':
            data = self.objects[key]
            response = {'VersionId':'wrong' if self.wrong_version else 'v1','ContentLength':len(data)}
            if '--range' in args:
                start,end = map(int,value('--range').removeprefix('bytes=').split('-'))
                response['ContentRange'] = f'bytes {start}-{end}/{len(data)}'
                data = data[start:end+1]
                if self.corrupt_range:data = bytes([data[0]^1])+data[1:]
                response['ContentLength'] = len(data)
            Path(args[-1]).write_bytes(data)
            return response
        return {'VersionId':'v1'}


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)
        self.aws = FakeAws()
        self.data = b'123456789'*9
        self.path = self.root/'dump.gz'
        self.path.write_bytes(self.data)
        self.scratch = self.root/'scratch'
    def test_multipart_and_range_hash_keep_only_one_part(self):
        response = pub.upload(self.aws,self.path,'dataset',self.scratch,part_bytes=11)
        got = pub.verify_remote(self.aws,'dataset',response,hashlib.sha256(self.data).hexdigest(),
            len(self.data),self.scratch,part_bytes=7)
        self.assertEqual(got['bytes'],len(self.data))
        self.assertFalse(self.scratch.exists())
        self.assertEqual(self.aws.objects['dataset'],self.data)
        self.assertEqual(len([x for x in self.aws.calls if x[0]=='upload-part']),8)
    def test_multipart_failure_aborts_and_removes_scratch(self):
        self.aws.fail_part=True
        with self.assertRaises(pub.AwsError):pub.upload(self.aws,self.path,'dataset',self.scratch,11)
        self.assertEqual(self.aws.calls[-1][0],'abort-multipart-upload')
        self.assertFalse(self.scratch.exists())
        self.assertNotIn('dataset',self.aws.objects)
    def test_changed_range_bytes_are_rejected(self):
        self.aws.objects['dataset']=self.data
        self.aws.corrupt_range=True
        with self.assertRaises(Exception):
            pub.verify_remote(self.aws,'dataset',{'VersionId':'v1'},hashlib.sha256(self.data).hexdigest(),
                len(self.data),self.scratch,7)
        self.assertFalse(self.scratch.exists())
    def test_changed_version_is_rejected(self):
        self.aws.objects['dataset']=self.data
        self.aws.wrong_version=True
        with self.assertRaises(Exception):
            pub.verify_remote(self.aws,'dataset',{'VersionId':'v1'},hashlib.sha256(self.data).hexdigest(),
                len(self.data),self.scratch,7)
    def publish_fixture(self):
        release=self.root/'source';release.mkdir()
        for name in [pub.MARKER,'SHA256SUMS.json','airbob-growth.sql.gz']+[f'proof-{n}.json' for n in range(17)]:
            (release/name).write_bytes(b'{}\n')
        checks={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in release.iterdir() if p.name!='SHA256SUMS.json'}
        (release/'SHA256SUMS.json').write_text(json.dumps(checks))
        receipt=self.root/'receipt.json'
        with patch.object(pub,'validate',return_value=({},checks,{})):
            result=pub.publish(release,'global-growth-b-'+'a'*16,pub.BUCKET,self.root,receipt,self.aws,allow_small=True)
        return release,receipt,result
    def test_marker_is_last_and_completed_release_reuses_versions(self):
        release,receipt,result=self.publish_fixture()
        puts=[args[args.index('--key')+1] for op,args in self.aws.calls if op=='put-object']
        self.assertTrue(puts[-1].endswith('/'+pub.MARKER))
        self.assertEqual(result['state'],'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED')
        with patch.object(pub,'validate',return_value=({},json.loads((release/'SHA256SUMS.json').read_text()),{})):
            again=pub.publish(release,result['datasetId'],pub.BUCKET,self.root,self.root/'again.json',self.aws)
        self.assertTrue(again['alreadyPublished'])
        self.assertEqual(len(puts),len([1 for op,_ in self.aws.calls if op=='put-object']))
    def test_fetch_pins_receipt_and_versions_without_copying_release(self):
        source,receipt,result=self.publish_fixture()
        output=self.root/'fetched'
        with patch.object(fetcher,'validate'):
            got=fetcher.fetch(receipt,hashlib.sha256(receipt.read_bytes()).hexdigest(),result['datasetId'],
                output,self.root,aws=self.aws,allow_small=True)
        self.assertEqual({p.name:p.read_bytes() for p in source.iterdir()},
            {p.name:p.read_bytes() for p in output.iterdir()})
        self.assertFalse(output.with_name('fetched.partial').exists())
        self.assertEqual(got['duplicateReleaseBytes'],0)
    def test_bad_receipt_never_downloads(self):
        _,receipt,result=self.publish_fixture()
        count=len(self.aws.calls)
        with self.assertRaises(Exception):
            fetcher.fetch(receipt,'0'*64,result['datasetId'],self.root/'bad',self.root,aws=self.aws)
        self.assertEqual(len(self.aws.calls),count)
    def test_failed_fetch_never_publishes_output_directory(self):
        _,receipt,result=self.publish_fixture()
        self.aws.wrong_version=True
        output=self.root/'bad'
        with self.assertRaises(Exception):
            fetcher.fetch(receipt,hashlib.sha256(receipt.read_bytes()).hexdigest(),result['datasetId'],
                output,self.root,aws=self.aws)
        self.assertFalse(output.exists())
    def test_artifact_changed_during_validation_cannot_publish_marker(self):
        release,_,result=self.publish_fixture()
        checks=json.loads((release/'SHA256SUMS.json').read_text())
        aws=FakeAws()
        def changed(*args,**kwargs):
            (release/'airbob-growth.sql.gz').write_bytes(b'unsealed replacement')
            return {},checks,{}
        with patch.object(pub,'validate',side_effect=changed),self.assertRaises(Exception):
            pub.publish(release,result['datasetId'],pub.BUCKET,self.root,self.root/'changed.json',aws)
        self.assertNotIn('datasets/'+result['datasetId']+'/'+pub.MARKER,aws.objects)
        self.assertEqual(json.loads((self.root/'changed.json').read_text())['state'],'FAILED')
    def test_checksum_file_changed_during_validation_is_rejected_before_aws(self):
        release,_,result=self.publish_fixture()
        checks=json.loads((release/'SHA256SUMS.json').read_text())
        aws=FakeAws()
        def changed(*args,**kwargs):
            (release/'SHA256SUMS.json').write_text('{}')
            return {},checks,{}
        with patch.object(pub,'validate',side_effect=changed),self.assertRaises(Exception):
            pub.publish(release,result['datasetId'],pub.BUCKET,self.root,self.root/'changed.json',aws)
        self.assertEqual(aws.calls,[])
    def test_fetch_preserves_a_destination_created_during_download(self):
        _,receipt,result=self.publish_fixture()
        output=self.root/'concurrent'
        original=self.aws.call
        def concurrently_created(service,operation,*args):
            response=original(service,operation,*args)
            if operation=='get-object' and args[args.index('--key')+1].endswith('/'+pub.MARKER):
                output.mkdir()
            return response
        with patch.object(self.aws,'call',side_effect=concurrently_created),patch.object(fetcher,'validate'),self.assertRaises(OSError):
            fetcher.fetch(receipt,hashlib.sha256(receipt.read_bytes()).hexdigest(),result['datasetId'],
                output,self.root,aws=self.aws)
        self.assertEqual(list(output.iterdir()),[])
        self.assertTrue(output.with_name('concurrent.partial').is_dir())
    def test_abort_failure_preserves_original_error_and_upload_id(self):
        self.aws.fail_part=True;self.aws.fail_abort=True
        events=[]
        with self.assertRaises(pub.AwsError) as error:
            pub.upload(self.aws,self.path,'dataset',self.scratch,11,recovery=events.append)
        self.assertEqual(error.exception.code,'InjectedFailure')
        self.assertEqual(events[-1]['state'],'ABORT_FAILED')
        self.assertEqual(events[-1]['uploadId'],'u1')
        self.assertFalse(self.scratch.exists())
    def test_sigterm_aborts_multipart_and_records_failure(self):
        release,_,result=self.publish_fixture()
        checks=json.loads((release/'SHA256SUMS.json').read_text())
        aws=FakeAws();aws.terminate_part=True
        real_upload=pub.upload
        def small_parts(*args,**kwargs):return real_upload(*args,**kwargs,part_bytes=11)
        previous=signal.getsignal(signal.SIGTERM)
        destination=self.root/'terminated.json'
        with patch.object(pub,'validate',return_value=({},checks,{})),patch.object(pub,'upload',side_effect=small_parts),self.assertRaises(pub.PublicationInterrupted):
            pub.publish(release,result['datasetId'],pub.BUCKET,self.root,destination,aws)
        self.assertEqual(signal.getsignal(signal.SIGTERM),previous)
        report=json.loads(destination.read_text())
        self.assertEqual(report['state'],'FAILED')
        self.assertEqual(report['multipartUploads']['SHA256SUMS.json']['state'],'ABORTED')
        self.assertNotIn('datasets/'+result['datasetId']+'/'+pub.MARKER,aws.objects)


if __name__=='__main__':unittest.main()
