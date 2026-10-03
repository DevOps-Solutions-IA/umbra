"""Bounded synthetic relay connection lifecycle, without addresses/paths/headers/body."""
from collections import OrderedDict
import time


class ConnectionDiagnostics:
    def __init__(self, capacity=128, clock=time.monotonic_ns):
        if not 1 <= capacity <= 128:
            raise ValueError('Invalid diagnostic capacity')
        self.capacity, self.clock = capacity, clock
        self.rows = OrderedDict()
        self.created = self.dropped = 0

    def opened(self):
        self.created += 1
        if len(self.rows) == self.capacity:
            self.rows.popitem(last=False)
            self.dropped += 1
        self.rows[self.created] = {'id': self.created, 'openedNanos': self.clock(),
                                  'responses': 0, 'receivedEvents': 0}
        return self.created

    def event(self, identifier, kind, *, incomplete=False, error=False):
        if kind not in ('received', 'response', 'keepalive', 'closed'):
            raise ValueError('Unknown diagnostic event')
        row = self.rows.get(identifier)
        if row is None:
            return
        row[kind+'Nanos'] = self.clock()
        if kind == 'received': row['receivedEvents'] += 1
        if kind == 'response': row['responses'] += 1
        if kind == 'closed':
            row['incompleteResponse'] = bool(incomplete)
            row['transportError'] = bool(error)

    def snapshot(self):
        return {'version': 1, 'scope': 'synthetic HTTPS server connection lifecycle only',
                'created': self.created, 'dropped': self.dropped,
                'connections': [dict(row) for row in self.rows.values()]}


class DenialDiagnostics:
    """Lab-only ASGI observer; retains fixed metadata for at most 128 denied responses."""
    DETAILS = {'Admission unavailable': 'ADMISSION_UNAVAILABLE',
               'Unauthorized': 'CAPABILITY_UNAUTHORIZED',
               'Storage temporarily unavailable': 'STORAGE_UNAVAILABLE',
               'Invalid request schema': 'INVALID_SCHEMA',
               'Invalid expiry': 'INVALID_EXPIRY',
               'Message id mismatch': 'MESSAGE_ID_MISMATCH',
               'Invalid or expired invitation': 'INVITATION_UNAVAILABLE',
               'Mailbox quota exceeded': 'MAILBOX_QUOTA',
               'Mailbox retention quota exceeded': 'MAILBOX_RETENTION_QUOTA'}

    def __init__(self, capacity=128, clock=time.monotonic_ns):
        if type(capacity) is not int or not 1 <= capacity <= 128:
            raise ValueError('Invalid diagnostic capacity')
        from collections import deque
        self.rows = deque(maxlen=capacity)
        self.capacity, self.clock = capacity, clock
        self.total = self.dropped = 0
        self.status_counts = {}

    @staticmethod
    def route(scope):
        import re
        path, method = scope.get('path', ''), scope.get('method', '')
        if path == '/v1/admission/challenge-batch' and method == 'POST':
            return 'ADMISSION_BATCH'
        if re.fullmatch(r'/v1/boxes/[^/]+/messages/[^/]+', path):
            return {'DELETE': 'MESSAGE_ACK', 'PUT': 'MESSAGE_SEND'}.get(method, 'OTHER')
        if re.fullmatch(r'/v1/boxes/[^/]+/messages', path):
            return {'GET': 'MESSAGE_POLL'}.get(method, 'OTHER')
        return 'OTHER'

    def wrap(self, app):
        async def observed(scope, receive, send):
            if scope['type'] != 'http':
                return await app(scope, receive, send)
            began = self.clock()
            status, payload, overflow = None, bytearray(), False
            fresh_challenge = False
            async def observed_send(message):
                nonlocal status, overflow, fresh_challenge
                if message['type'] == 'http.response.start':
                    status = message['status']
                    values = [value for name, value in message.get('headers', ())
                              if name.lower() == b'x-umbra-admission-retry']
                    fresh_challenge = status == 403 and values == [b'fresh-challenge']
                elif message['type'] == 'http.response.body' and status is not None and status >= 300:
                    chunk = message.get('body', b'')
                    if not overflow and len(payload) + len(chunk) <= 256:
                        payload.extend(chunk)
                    else:
                        payload.clear(); overflow = True
                    if not message.get('more_body', False):
                        import json
                        category = 'OTHER'
                        if not overflow:
                            try:
                                value = json.loads(payload)
                                if isinstance(value, dict) and set(value) == {'detail'} and isinstance(value['detail'], str):
                                    category = self.DETAILS.get(value['detail'], 'OTHER')
                            except (ValueError, UnicodeError):
                                pass
                        payload.clear()
                        self.total += 1
                        if len(self.rows) == self.capacity:self.dropped += 1
                        # HTTP status values have a fixed bounded domain; never retain custom text.
                        code = status if type(status) is int and 300 <= status <= 599 else 0
                        self.status_counts[code] = self.status_counts.get(code, 0) + 1
                        self.rows.append({'status': code, 'route': self.route(scope), 'denial': category,
                                          'freshChallengeRequired': fresh_challenge,
                                          'startedNanos': began, 'completedNanos': self.clock()})
                await send(message)
            await app(scope, receive, observed_send)
        return observed

    def snapshot(self):
        return {'total': self.total, 'dropped': self.dropped,
                'statusCounts': dict(self.status_counts), 'responses': [dict(row) for row in self.rows]}


class AdmissionRejectionDiagnostics:
    """Lab-only rejection stage; never retain credentials, proofs or exception text."""
    def __init__(self, capacity=128, clock=time.monotonic_ns):
        if type(capacity) is not int or not 1 <= capacity <= 128:
            raise ValueError('Invalid diagnostic capacity')
        from collections import deque
        from threading import Lock
        self.rows = deque(maxlen=capacity)
        self.lock = Lock()
        self.clock = clock
        self.total = self.dropped = self.diagnostic_failures = 0

    def wrap_consume(self, consume):
        def observed(*args, **kwargs):
            from umbra_relay.admission_protocol import AdmissionError, Challenge, ChallengeUnavailable
            try:
                return consume(*args, **kwargs)
            except AdmissionError as rejected:
                try:
                    stage = 'OTHER'
                    if isinstance(rejected, ChallengeUnavailable):
                        stage = 'CHALLENGE_UNAVAILABLE'
                    else:
                        trace = rejected.__traceback__
                        while trace is not None:
                            frame = trace.tb_frame
                            if frame.f_code is Challenge.validate.__code__:
                                # Original time relation only; does not assert bindings valid.
                                now, fields = frame.f_locals['now'], frame.f_locals['p']
                                stage = ('CHALLENGE_NOT_STARTED' if now < int(fields[6]) else
                                         'CHALLENGE_WALL_EXPIRED' if now >= int(fields[7]) else
                                         'CHALLENGE_BINDING')
                            elif frame.f_code is Challenge.verify_proof.__code__:
                                stage = 'CHALLENGE_PROOF'
                            trace = trace.tb_next
                    observed_nanos = self.clock()
                    with self.lock:
                        self.total += 1
                        if len(self.rows) == self.rows.maxlen:
                            self.dropped += 1
                        self.rows.append({'stage': stage, 'observedNanos': observed_nanos})
                except Exception:
                    # A diagnostic failure is reported but cannot change admission behavior.
                    with self.lock:
                        self.diagnostic_failures += 1
                raise  # Same exception; no retry, response or authorization change.
        return observed

    def snapshot(self):
        with self.lock:
            return {'total': self.total, 'dropped': self.dropped,
                    'diagnosticFailures': self.diagnostic_failures,
                    'rejections': [dict(row) for row in self.rows]}
