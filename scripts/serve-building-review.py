#!/usr/bin/env python3
"""Serve one generated review HTML on loopback only; no directory or data API."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--html",type=Path,required=True)
    parser.add_argument("--port",type=int,default=8090)
    args=parser.parse_args()
    page=args.html.resolve()
    if not page.is_file() or page.suffix.lower()!=".html" or not 1024<=args.port<=65535:
        parser.error("Expected an existing HTML file and a port between 1024 and 65535.")

    class Handler(BaseHTTPRequestHandler):
        def serve(self,body):
            if urlsplit(self.path).path not in {"/","/index.html"}:
                self.send_error(404)
                return
            payload=page.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type","text/html; charset=utf-8")
            self.send_header("Content-Length",str(len(payload)))
            self.send_header("Cache-Control","no-store")
            self.send_header("X-Content-Type-Options","nosniff")
            self.send_header("Referrer-Policy","no-referrer")
            self.end_headers()
            if body:
                self.wfile.write(payload)

        def do_GET(self):
            self.serve(True)

        def do_HEAD(self):
            self.serve(False)

        def log_message(self,*args):
            pass

    server=ThreadingHTTPServer(("127.0.0.1",args.port),Handler)
    print(f"Building review: http://127.0.0.1:{args.port}/",flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__=="__main__":
    main()
