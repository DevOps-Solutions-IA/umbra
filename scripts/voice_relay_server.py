"""Same pinned uvicorn/h11 laboratory relay, with payload-free closure diagnostics."""
import argparse
import json
from pathlib import Path
from voice_http_diagnostics import ConnectionDiagnostics


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--fd',type=int,required=True)
    parser.add_argument('--cert',type=Path,required=True)
    parser.add_argument('--key',type=Path,required=True)
    parser.add_argument('--diagnostics',type=Path,required=True)
    args=parser.parse_args()
    import uvicorn
    from uvicorn.protocols.http.h11_impl import H11Protocol
    diagnostic=ConnectionDiagnostics()
    def snapshot():
        args.diagnostics.write_text(json.dumps(diagnostic.snapshot(),indent=2)+'\n')
    class ObservedServer(uvicorn.Server):
        async def shutdown(self,sockets=None):
            try:await super().shutdown(sockets=sockets)
            finally:snapshot()
    class ObservedH11(H11Protocol):
        def connection_made(self,transport):
            self.diagnostic_id=diagnostic.opened()
            super().connection_made(transport)
        def data_received(self,data):
            diagnostic.event(self.diagnostic_id,'received')
            super().data_received(data)
        def on_response_complete(self):
            diagnostic.event(self.diagnostic_id,'response')
            super().on_response_complete()
        def timeout_keep_alive_handler(self):
            diagnostic.event(self.diagnostic_id,'keepalive')
            super().timeout_keep_alive_handler()
        def connection_lost(self,exc):
            diagnostic.event(self.diagnostic_id,'closed',
                             incomplete=bool(self.cycle and not self.cycle.response_complete),error=exc is not None)
            super().connection_lost(exc)
    try:
        # No keepalive/HTTP/TLS deadline change. Locked dependencies use h11 already.
        config=uvicorn.Config('umbra_relay.app:create_app',factory=True,fd=args.fd,workers=1,
                    ssl_certfile=str(args.cert),ssl_keyfile=str(args.key),
                    http=ObservedH11,access_log=False,proxy_headers=False,log_level='warning')
        ObservedServer(config).run()
    finally:
        snapshot()


if __name__=='__main__':main()
