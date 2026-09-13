"""AWS media dates and real local Java decode; no HTTP/cloud execution."""
import datetime as dt,json,os,shutil,struct,subprocess,sys,tempfile,time,unittest,zlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]/'scripts'
sys.path.insert(0,str(ROOT))
import growth_b_media as m
class AvailabilityTest(unittest.TestCase):
 def value(self,day,ranges=None):return {'booking_window_start_inclusive':day.isoformat(),'booking_window_end_exclusive':m.plus_months(day,3).isoformat(),'unavailable_ranges':ranges or []}
 def observe(self,value,before,after=None,zone='Asia/Seoul'):return m.availability(value,zone,before,after or before)
 def test_month_length_clips_without_using_inventory_horizon(self):
  self.assertEqual(m.plus_months(dt.date(2026,11,30),3),dt.date(2027,2,28))
  self.assertEqual(m.plus_months(dt.date(2023,11,30),3),dt.date(2024,2,29))
 def test_listing_local_midnight_not_utc_day(self):
  when=dt.datetime(2026,9,12,15,1,tzinfo=dt.timezone.utc)
  out=self.observe(self.value(dt.date(2026,9,13)),when)
  self.assertEqual(out['windowDays'],91);self.assertEqual(out['reservableNights'],91)
 def test_north_america_day_can_differ(self):
  when=dt.datetime(2026,9,13,2,tzinfo=dt.timezone.utc)
  self.assertEqual(self.observe(self.value(dt.date(2026,9,12)),when,zone='America/Los_Angeles')['localDate'],'2026-09-12')
 def test_request_crossing_midnight_rejects_instead_of_accepting_old_day(self):
  a=dt.datetime(2026,9,12,14,59,59,tzinfo=dt.timezone.utc);b=a+dt.timedelta(seconds=2)
  with self.assertRaisesRegex(m.Rejected,'LOCAL_DATE_CHANGED'):self.observe(self.value(dt.date(2026,9,12)),a,b)
 def test_wrong_utc_window_rejected(self):
  when=dt.datetime(2026,9,12,15,1,tzinfo=dt.timezone.utc)
  with self.assertRaisesRegex(m.Rejected,'THREE_MONTH'):self.observe(self.value(dt.date(2026,9,12)),when)
 def test_98_day_inventory_window_rejected(self):
  when=dt.datetime(2026,9,13,2,tzinfo=dt.timezone.utc);v=self.value(dt.date(2026,9,13));v['booking_window_end_exclusive']='2026-12-20'
  with self.assertRaisesRegex(m.Rejected,'THREE_MONTH'):self.observe(v,when)
 def test_unavailable_ranges_sum_without_expanding_rows(self):
  when=dt.datetime(2026,9,13,2,tzinfo=dt.timezone.utc);v=self.value(dt.date(2026,9,13),[{'start_date':'2026-09-13','end_date_exclusive':'2026-09-15'},{'start_date':'2026-09-15','end_date_exclusive':'2026-09-18'}])
  self.assertEqual(self.observe(v,when)['reservableNights'],86)
 def test_overlap_rejected(self):
  when=dt.datetime(2026,9,13,2,tzinfo=dt.timezone.utc);v=self.value(dt.date(2026,9,13),[{'start_date':'2026-09-13','end_date_exclusive':'2026-09-18'},{'start_date':'2026-09-15','end_date_exclusive':'2026-09-20'}])
  with self.assertRaisesRegex(m.Rejected,'OVERLAP'):self.observe(v,when)
 def test_reversed_and_outside_ranges_rejected(self):
  when=dt.datetime(2026,9,13,2,tzinfo=dt.timezone.utc)
  for a,b in [('2026-09-15','2026-09-13'),('2026-09-12','2026-09-15'),('2026-12-12','2026-12-14')]:
   with self.subTest(a=a,b=b),self.assertRaises(m.Rejected):self.observe(self.value(dt.date(2026,9,13),[{'start_date':a,'end_date_exclusive':b}]),when)
 def test_no_free_night_cannot_pass(self):
  when=dt.datetime(2026,9,13,2,tzinfo=dt.timezone.utc)
  with self.assertRaisesRegex(m.Rejected,'NO_RESERVABLE'):self.observe(self.value(dt.date(2026,9,13),[{'start_date':'2026-09-13','end_date_exclusive':'2026-12-13'}]),when)
 def test_duplicate_json_rejected(self):
  with self.assertRaises(m.Rejected):m.json_bytes(b'{"a":1,"a":2}')
 def test_public_image_hosts_and_scheme_fail_before_get(self):
  p=m.Probe('http://127.0.0.1:8080',ROOT/'GlobalBImageDecode.java',guard=lambda:None,deadline=time.monotonic()+10)
  for url in ['http://a0.muscache.com/p.jpg','https://127.0.0.1/p.jpg','https://a0.muscache.com@other.invalid/p.jpg','https://a0.muscache.com:8443/p.jpg']:
   with self.subTest(url=url),self.assertRaises(m.Rejected):p.decode(url,{'a0.muscache.com'})
  self.assertEqual(p.requests,0)
 def test_decoder_change_rejected_before_fetch(self):
  with tempfile.TemporaryDirectory() as d:
   f=Path(d)/'X.java';f.write_text('original');p=m.Probe('http://127.0.0.1:8080',f,guard=lambda:None,deadline=time.monotonic()+10);f.write_text('changed')
   with self.assertRaisesRegex(m.Rejected,'DECODER_CHANGED'):p.decode('https://a0.muscache.com/p.jpg',{'a0.muscache.com'})
   self.assertEqual(p.requests,0)
class DecodeTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  candidate=Path(os.environ['JAVA_HOME'])/'bin/java' if os.environ.get('JAVA_HOME') else shutil.which('java')
  if not candidate:raise unittest.SkipTest('Local Java unavailable')
  cls.java=Path(candidate)
 def png(self):
  def chunk(name,data):return struct.pack('>I',len(data))+name+data+struct.pack('>I',zlib.crc32(name+data)&0xffffffff)
  return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\x12\x34\x56'))+chunk(b'IEND',b'')
 def run_decoder(self,raw):return subprocess.run([str(self.java),'-Xmx256m','-Djava.awt.headless=true',str(ROOT/'GlobalBImageDecode.java')],input=raw,capture_output=True,timeout=20)
 def test_real_java_full_decode(self):
  r=self.run_decoder(self.png());self.assertEqual(r.returncode,0);self.assertEqual(json.loads(r.stdout),{'fullyDecoded':True,'width':1,'height':1})
 def test_invalid_or_truncated_image_rejected(self):
  for raw in (b'<html>failure</html>',self.png()[:44]):
   with self.subTest(bytes=len(raw)):self.assertNotEqual(self.run_decoder(raw).returncode,0)
if __name__=='__main__':unittest.main()
