import sys
import os
import json
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler

LOG_FILE = r"c:\Users\Admin\Desktop\ABS\scratch\test_server_requests.log"

# Clear log file on startup
with open(LOG_FILE, "w", encoding="utf-8") as f:
    f.write("")

request_count = 0
lock = threading.Lock()

class TestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self.handle_req()

    def do_POST(self):
        self.handle_req()

    def handle_req(self):
        global request_count
        content_len = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_len).decode('utf-8', errors='replace') if content_len > 0 else ""

        with lock:
            request_count += 1
            idx = request_count
            record = {
                "index": idx,
                "method": self.command,
                "path": self.path,
                "headers": dict(self.headers),
                "body": body
            }
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                f.write(json.dumps(record) + "\n")
                f.flush()

        response_body = json.dumps({"status": "received", "index": idx}).encode('utf-8')
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response_body)))
        self.end_headers()
        self.wfile.write(response_body)

    def log_message(self, format, *args):
        # Suppress default noisy console logging
        sys.stderr.write(f"[{self.log_date_time_string()}] {format % args}\n")

if __name__ == "__main__":
    port = 8888
    server = HTTPServer(("0.0.0.0", port), TestHandler)
    print(f"Test server running on port {port}...")
    server.serve_forever()
