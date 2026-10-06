"""One bounded Android pooled-socket race probe; no retry and no product policy change."""
import json
import time


def associate(snapshot, previous_ids):
    rows=[row for row in snapshot['connections'] if row['id'] not in previous_ids]
    if len(rows)!=1 or rows[0]['responses']!=1 or 'keepaliveNanos' in rows[0] or 'closedNanos' in rows[0]:
        raise RuntimeError('Probe did not isolate exactly one warm live HTTPS connection')
    return dict(rows[0])


def idle_closed(snapshot, warm):
    rows=[row for row in snapshot['connections'] if row['id']==warm['id']]
    if len(rows)!=1:raise RuntimeError('Owned HTTPS probe connection missing')
    row=rows[0]
    if row['responses']!=1 or row['receivedEvents']!=warm['receivedEvents']:
        raise RuntimeError('Probe request reached server before owned idle closure')
    if 'keepaliveNanos' not in row:return None
    if row['keepaliveNanos']<warm['responseNanos']:
        raise RuntimeError('Invalid HTTPS idle closure ordering')
    return {key:row[key] for key in ('id','responseNanos','keepaliveNanos')}


def coordinate(path, previous_ids, read, write, report, *, clock=time.monotonic, pause=time.sleep):
    if read('synthetic-http-warmed.json')!={'warmed':True}:raise RuntimeError('Missing HTTPS warmup')
    warm=associate(json.loads(path.read_text()),previous_ids)
    write('synthetic-http-associated.json',{'associated':True})
    if read('synthetic-http-blocked.json')!={'blocked':True}:raise RuntimeError('Missing HTTPS write barrier')
    deadline=clock()+8
    observed=None
    while clock()<deadline:
        observed=idle_closed(json.loads(path.read_text()),warm)
        if observed is not None:break
        pause(.05)
    if observed is None:raise RuntimeError('Owned HTTPS idle close not observed')
    write('synthetic-http-closed.json',{'idleCloseObserved':True})
    result=read('synthetic-http-reproduced.json')
    if result!={'androidEof':True,'networkRevoked':True,'freshSocketVerified':True}:raise RuntimeError('Android pooled EOF not reproduced')
    report.write_text(json.dumps({'scope':'controlled legacy Android pooled TLS race plus fresh-socket fixed transport; not historical socket attribution',
                                 'result':'REPRODUCED','server':observed,'client':result},indent=2)+'\n')
