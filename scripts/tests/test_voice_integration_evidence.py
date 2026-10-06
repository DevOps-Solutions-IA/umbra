"""Report gates only; not multimedia acceptance evidence."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch, Mock
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from run_voice_integration import valid_audio, valid_report, valid_stop, valid_emergency_stop, valid_impairment, valid_processing, coordinate_mute, processing_barrier, issue_turn_after_selection, require_completed_run, await_expired_turn_timestamp
import voice_network_evidence as network

class VoiceEvidenceTest(unittest.TestCase):
    def test_expired_turn_waits_past_the_entire_integer_expiry_second(self):
        clock=Mock(side_effect=[101.0,101.1,101.9,102.0])
        sleep=Mock()
        await_expired_turn_timestamp(101,clock=clock,monotonic=lambda:0,sleep=sleep)
        self.assertEqual(4,clock.call_count)
        self.assertEqual(3,sleep.call_count)

    def test_expired_turn_wait_is_bounded_when_wall_clock_stalls(self):
        with self.assertRaises(RuntimeError):
            await_expired_turn_timestamp(101,clock=lambda:100,monotonic=Mock(side_effect=[0,0,4]),sleep=Mock())

    def test_early_success_return_or_empty_receipt_cannot_approve_media(self):
        import tempfile,json
        for result in (None,True,{}, {'syntheticCredential':True}):
            with self.assertRaises(RuntimeError):require_completed_run(result)
        with tempfile.TemporaryDirectory() as d:
            receipt=Path(d)/'voice-evidence.json'
            with self.assertRaises(RuntimeError):require_completed_run(receipt)
            receipt.write_text('{}')
            with self.assertRaises(RuntimeError):require_completed_run(receipt)
            good={'synthetic':True,'endpoints':2,'observedSeconds':1,
                  'audio':[{'rejectedBeforeCapture':True}]*2,'network':[{'syntheticObservation':1}]*2}
            receipt.write_text(json.dumps(good));require_completed_run(receipt)
            for field in good:
                bad=good.copy();del bad[field];receipt.write_text(json.dumps(bad))
                with self.assertRaises(RuntimeError):require_completed_run(receipt)

    def test_actual_emergency_receipt_includes_strict_snapshot_counter(self):
        # Exact nonsecret receipt from aa0ccfe artifact11269409884, emulator5554.
        good=dict(failedClosed=True,nativeCaptureQuietAfterMillis=1000,
                  nativeCaptureObservedMillis=500,lateCaptureCallbacks=0,
                  expiredDeliveriesRejected=0,expiredSnapshotRejections=0,
                  emergencyState="CLOSED",requestedNanos=122672453510,
                  invalidatedNanos=122672457167,confirmedNanos=122715853374,
                  lateVideoCallbacks=0,lastAudioCaptureNanos=122711424586,
                  lastVideoCaptureNanos=0)
        self.assertTrue(valid_emergency_stop(good))
        missing=dict(good);del missing["expiredSnapshotRejections"]
        self.assertFalse(valid_emergency_stop(missing))
        for value in (-1,True,1,1401):
            self.assertFalse(valid_emergency_stop({**good,"expiredSnapshotRejections":value}))
        self.assertFalse(valid_emergency_stop({**good,"unknown":0}))
        base={key:good[key] for key in ("failedClosed","nativeCaptureQuietAfterMillis",
            "nativeCaptureObservedMillis","lateCaptureCallbacks","expiredDeliveriesRejected",
            "expiredSnapshotRejections")}
        for value in (0,1,1400):
            self.assertTrue(valid_stop({**base,"expiredSnapshotRejections":value},expected_expiry=True))
        for value in (-1,True,1401):
            self.assertFalse(valid_stop({**base,"expiredSnapshotRejections":value},expected_expiry=True))
        missing=dict(base);del missing["expiredSnapshotRejections"]
        self.assertFalse(valid_stop(missing,expected_expiry=True))
        self.assertFalse(valid_stop({**base,"unknown":0},expected_expiry=True))

    def test_turn_issued_once_only_after_both_selected_engines_request_it(self):
        events=[]
        def read(serial,*args):
            events.append(('selected',serial));return {'selectedAndConsented':True}
        def issue():
            self.assertEqual([('selected','A'),('selected','B')],events[:2])
            events.append(('issued',));return {'synthetic':True}
        write=Mock()
        issue_turn_after_selection(('A','B'),{'A':object(),'B':object()},123,write,read,issue)
        self.assertEqual(2,events.count(('issued',)))
        self.assertEqual(2,write.call_count)

    def test_turn_not_issued_when_either_engine_lacks_selection(self):
        for reports in (({},),({'selectedAndConsented':True},{'selectedAndConsented':False})):
            issue=Mock();write=Mock()
            with self.assertRaises(RuntimeError):
                issue_turn_after_selection(('A','B'),{'A':object(),'B':object()},123,write,Mock(side_effect=reports),issue)
            issue.assert_not_called();write.assert_not_called()

    def test_video_expiry_uses_remaining_scenario_budget_without_renewal(self):
        # Native fixture began before selection. The host epoch precedes either
        # ready receipt, so subtracting ALL elapsed host time is conservative.
        rows=[{'selectedAndConsented':True,'remainingFixtureMillis':118_000},
              {'selectedAndConsented':True,'remainingFixtureMillis':117_000}]
        issued=[]
        def issue(ttl):issued.append(ttl);return {'synthetic':True}
        receipt=issue_turn_after_selection(('A','B'),{'A':object(),'B':object()},240,Mock(),
            Mock(side_effect=rows),issue,expiry_started=100,clock=Mock(side_effect=[103,103.1,104,104.1]))
        self.assertEqual([103,102],issued)
        # Observed initial video + STOP coordination + allowed25s renegotiation
        # can legitimately exceed60s; each credential is still issued exactly once.
        self.assertGreater(min(issued),38+23+25)
        self.assertEqual(10_000,receipt['closureReserveMillis'])
        self.assertLessEqual(issued[0]*1000+3000+10_000,117_000)

    def test_video_expiry_respects_earlier_host_deadline(self):
        rows=[{'selectedAndConsented':True,'remainingFixtureMillis':118_000}]*2
        issue=Mock(return_value={'synthetic':True})
        issue_turn_after_selection(('A','B'),{'A':object(),'B':object()},130,Mock(),
            Mock(side_effect=rows),issue,expiry_started=100,clock=lambda:103)
        self.assertEqual([16,16],[call.args[0] for call in issue.call_args_list])

    def test_video_expiry_rejects_slow_or_backwards_issuance_without_delivery(self):
        for end in (104.001,102):
            rows=[{'selectedAndConsented':True,'remainingFixtureMillis':118_000}]*2
            issue=Mock(return_value={'synthetic':True});write=Mock()
            with self.assertRaises(RuntimeError):
                issue_turn_after_selection(('A','B'),{'A':object(),'B':object()},240,write,
                    Mock(side_effect=rows),issue,expiry_started=100,clock=Mock(side_effect=[103,end]))
            self.assertEqual(1,issue.call_count)
            write.assert_not_called()

    def test_video_expiry_cannot_extend_deadline_or_ignore_invalid_remaining(self):
        for remaining in (True,0,-1,120_001,10_000):
            issue=Mock()
            rows=[{'selectedAndConsented':True,'remainingFixtureMillis':remaining}]*2
            with self.assertRaises(RuntimeError):
                issue_turn_after_selection(('A','B'),{'A':object(),'B':object()},140,Mock(),
                    Mock(side_effect=rows),issue,expiry_started=100,clock=lambda:103)
            issue.assert_not_called()

    def test_modulation_needs_decoded_output_and_positive_observation_windows(self):
        good=dict(natural=0,modified=80,loud=80,settleMillis=1300,observedMillis=2000,videoFrames=10,step=0,metrics=[200]*34)
        self.assertTrue(valid_processing(good,"modified",video=True))
        for field in good:
            bad=good.copy();del bad[field];self.assertFalse(valid_processing(bad,"modified",video=True))
        for field,value in (("modified",0),("natural",40),("observedMillis",0),("settleMillis",30000),("videoFrames",0),("metrics",[])):
            self.assertFalse(valid_processing({**good,field:value},"modified",video=True))
        self.assertFalse(valid_processing(good,"natural"))
        self.assertFalse(valid_processing(good,"quiet"))
        self.assertTrue(valid_processing({**good,"modified":0,"loud":0},"quiet"))

    def test_netem_equivalent_decimal_format_still_requires_all_bounds(self):
        good="qdisc netem 8002: root refcnt 2 limit 20 delay 80.0ms loss 2% rate 128Kbit"
        self.assertTrue(valid_impairment(good))
        self.assertTrue(valid_impairment(good.replace("80.0ms","80ms")))
        for before,after in (("80.0ms","800ms"),("limit 20","limit 1000"),("limit 20","limit 200"),("loss 2%","loss 0%"),("128Kbit","1Mbit"),("netem","noqueue")):
            self.assertFalse(valid_impairment(good.replace(before,after)))

    def test_ipv6_link_observation_also_rejects_other_ipv4_paths(self):
        sources=["fec0::10","fec0::20"]
        with patch.object(network,"count",side_effect=[100,0]) as counter:
            value=network.summarize_ipv6_turn(Path("synthetic.pcap"),"fd42::2","10.0.2.16",sources,43210,0,10,tls=False)
            self.assertEqual(100,value["turnIpv6Packets"])
            self.assertIn("IPv4 relay allocation",value["scope"])
            self.assertIn("src host 10.0.2.16",counter.call_args_list[1].args[1])
            for source in sources:self.assertIn("src host "+source,counter.call_args_list[1].args[1])
        for counts in ([0,0],[100,1]):
            with patch.object(network,"count",side_effect=counts):
                with self.assertRaises(RuntimeError):network.summarize_ipv6_turn(Path("synthetic.pcap"),"fd42::2","10.0.2.16",sources,43210,0,10,tls=True)

    def test_tls_capture_selects_device_not_entire_emulator_subnet(self):
        with patch.object(network,"count",side_effect=[100,0,0,0]) as counter:
            report=network.summarize_tls(Path("synthetic.pcap"),"172.20.0.2",43210,0,10,source_address="10.0.2.16")
            self.assertEqual(100,report["turnTlsPackets"])
            for call in counter.call_args_list:
                self.assertIn("src host 10.0.2.16",call.args[1])
                self.assertNotIn("src net",call.args[1])
        for values in ([0,0,0,0],[100,1,0,0],[100,0,1,0],[100,0,0,1]):
            with patch.object(network,"count",side_effect=values):
                with self.assertRaises(RuntimeError):
                    network.summarize_tls(Path("synthetic.pcap"),"172.20.0.2",43210,0,10,source_address="10.0.2.16")
        with self.assertRaises(ValueError):
            network.summarize_tls(Path("synthetic.pcap"),"172.20.0.2",43210,0,10,source_address="10.0.2.2")

    def test_ice_and_packet_counts_are_not_decoded_audio(self):
        good=dict(decodedBuffers=100,capturedBuffers=100,verifiedNativeTransport=True,receivedAudioPackets=50,codec="audio/opus",sdpAddressAudit=True)
        self.assertTrue(valid_audio(good))
        for field in good:
            bad=good.copy();del bad[field];self.assertFalse(valid_audio(bad))
        for field in ("decodedBuffers","capturedBuffers","receivedAudioPackets"):
            bad=good.copy();bad[field]=0;self.assertFalse(valid_audio(bad))

    def test_processing_waits_for_both_actions_and_rejects_old_step(self):
        writes=[]
        def read(serial,*unused):
            self.assertEqual([],writes)
            return {"step":1,"applied":True,"elapsedMillis":100 if serial=="a" else 9000}
        result=processing_barrier(("a","b"),{"a":1,"b":2},10,1,lambda *args:writes.append(args),read)
        self.assertEqual(2,len(writes));self.assertEqual(9000,result[1]["elapsedMillis"])
        write=Mock()
        with self.assertRaises(RuntimeError):
            processing_barrier(("a","b"),{"a":1,"b":2},10,2,write,lambda *args:{"step":1,"applied":True,"elapsedMillis":100})
        write.assert_not_called()

    def test_mute_waits_for_both_native_confirmations_before_observing(self):
        events=[]
        def read(serial,name,*unused):
            events.append(("read",serial,name))
            if name.endswith("applied.json"):
                self.assertFalse(any(e[0]=="write" and e[2].endswith("observe.json") for e in events))
                return {"applied":True,"elapsedMillis":100 if serial=="a" else 9000}
            return {"quiet":True,"observedMillis":1200,"decodedTones":0}
        result=coordinate_mute(("a","b"),{"a":1,"b":2},10,lambda serial,name,value:events.append(("write",serial,name)),read)
        self.assertEqual(2,len(result["observed"]))
        self.assertEqual(9000,result["applied"][1]["elapsedMillis"])

    def test_mute_missing_confirmation_or_empty_window_fails(self):
        applied={"applied":True,"elapsedMillis":100}
        for responses in ([applied,{"applied":False,"elapsedMillis":100}],
                          [applied,applied,{"quiet":True,"observedMillis":0,"decodedTones":0}],
                          [applied,applied,{"quiet":True,"observedMillis":1200,"decodedTones":4}]):
            with self.assertRaises(RuntimeError):coordinate_mute(("a","b"),{"a":1,"b":2},10,Mock(),Mock(side_effect=responses))

    def test_empty_crashed_or_failed_instrumentation_never_passes(self):
        good="engineVoice=PASS\nOK (3 tests)\nINSTRUMENTATION_CODE: -1\n"
        self.assertTrue(valid_report(good))
        self.assertFalse(valid_report(good,private_lock=True))
        self.assertTrue(valid_report(good+"\nprivateStartupLock=PASS old relay rejected; no reconnect",private_lock=True))
        for text in ("",good.replace("3 tests","0 tests"),good.replace("engineVoice=PASS",""),good+"INSTRUMENTATION_STATUS_CODE: -2",good+"Process crashed"):
            self.assertFalse(valid_report(text))

    def test_capture_requires_actual_turn_and_rejects_direct_attempts(self):
        with patch.object(network,"count",side_effect=[100,8,0,0]):
            self.assertEqual(0,network.summarize(Path("synthetic.pcap"),"172.20.0.2")["nonTurnStunPackets"])
        for counts in ([0,0,0,0],[100,0,0,0],[100,8,1,1],[100,8,0,1]):
            with patch.object(network,"count",side_effect=counts):
                with self.assertRaises(RuntimeError):network.summarize(Path("synthetic.pcap"),"172.20.0.2")

    def test_callee_without_remote_description_is_not_claimed_as_transport(self):
        with patch.object(network,"count",side_effect=[0,0,0,0]):
            report=network.summarize(Path("synthetic.pcap"),"172.20.0.2",require_turn=False)
            self.assertTrue(report["mediaAttempt"].startswith("NOT_EXECUTED"))
        with patch.object(network,"count",side_effect=[0,0,1,1]):
            with self.assertRaises(RuntimeError): network.summarize(Path("synthetic.pcap"),"172.20.0.2",require_turn=False)

    def test_state_flag_does_not_prove_native_capture_stopped(self):
        good={"failedClosed":True,"nativeCaptureQuietAfterMillis":1000,
              "nativeCaptureObservedMillis":500,"lateCaptureCallbacks":0,"expiredDeliveriesRejected":0,"expiredSnapshotRejections":0}
        self.assertTrue(valid_stop(good))
        self.assertTrue(valid_stop({**good,"expiredDeliveriesRejected":1},expected_expiry=True))
        self.assertFalse(valid_stop({**good,"expiredDeliveriesRejected":1}))
        self.assertFalse(valid_stop({"failedClosed":True}))
        for field,value in (("lateCaptureCallbacks",1),("nativeCaptureObservedMillis",0),
                            ("nativeCaptureQuietAfterMillis",30000),("failedClosed",False),
                            ("expiredDeliveriesRejected",-1),("expiredDeliveriesRejected",True),
                            ("expiredDeliveriesRejected",2),("unknown",0)):
            bad={**good,field:value}
            self.assertFalse(valid_stop(bad))
            self.assertFalse(valid_stop(bad,expected_expiry=True))
