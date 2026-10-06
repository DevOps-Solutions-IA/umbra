"""Bounded per-test monotonic timing; preserves the original total instrumentation timeout."""
import json
import re
import subprocess
import threading
import time


def run(command, stream, report, timeout=300):
    began=time.monotonic_ns()
    rows=[]; fields={}; active=None; errors=[]; dropped=0
    process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    def consume():
        nonlocal active,dropped
        try:
            for line in process.stdout:
                stream.write(line);stream.flush()
                match=re.fullmatch(r'INSTRUMENTATION_STATUS: (class|test|current|numtests)=(.*)\n?',line.rstrip('\n'))
                if match:fields[match[1]]=match[2]
                code=re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*',line)
                if not code:continue
                value=int(code[1]);clazz=fields.get('class','');method=fields.get('test','');fields.clear()
                if not re.fullmatch(r'app\.umbra\.[A-Za-z0-9_.$]{1,160}',clazz) or not re.fullmatch(r'[A-Za-z0-9_]{1,160}',method):continue
                if value==1:
                    active={'class':clazz,'test':method,'startedNanos':time.monotonic_ns()-began}
                elif active is not None and value in (0,-1,-2,-3,-4):
                    active['finishedNanos']=time.monotonic_ns()-began;active['statusCode']=value
                    if len(rows)<256:rows.append(active)
                    else:dropped+=1
                    active=None
        except Exception as failure:errors.append(type(failure).__name__)
        finally:process.stdout.close()
    reader=threading.Thread(target=consume,daemon=True);reader.start()
    timed_out=False
    try:
        process.wait(timeout=max(.001,timeout-(time.monotonic_ns()-began)/1e9))
    except subprocess.TimeoutExpired:
        timed_out=True;process.kill();process.wait()
    finally:
        reader.join(5)
        value={'scope':'host-observed Android instrumentation status; not device CPU time',
               'timeoutSeconds':timeout,'timedOut':timed_out,'elapsedNanos':time.monotonic_ns()-began,
               'completed':rows,'active':active,'dropped':dropped,'readerAlive':reader.is_alive(),
               'readerFailed':bool(errors),'exitCode':process.returncode}
        report.write_text(json.dumps(value,indent=2)+'\n')
    if reader.is_alive() or errors or dropped:raise RuntimeError('Instrumentation timing evidence incomplete')
    if timed_out:raise subprocess.TimeoutExpired(command,timeout)
    return subprocess.CompletedProcess(command,process.returncode)
