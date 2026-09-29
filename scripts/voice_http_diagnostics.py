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
