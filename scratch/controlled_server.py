from http.server import HTTPServer, BaseHTTPRequestHandler
import sys
import urllib.parse

class ControlledHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)
        size_kb = int(params.get('size', [10])[0])
        total_bytes = size_kb * 1024
        
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(total_bytes))
        self.end_headers()
        
        # Send in 8KB chunks
        chunk = b'A' * 8192
        sent = 0
        while sent < total_bytes:
            to_send = min(len(chunk), total_bytes - sent)
            self.wfile.write(chunk[:to_send])
            sent += to_send

    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        if content_length > 0:
            self.rfile.read(content_length)
            
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)
        size_kb = int(params.get('size', [50])[0])
        total_bytes = size_kb * 1024
        
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(total_bytes))
        self.end_headers()
        
        chunk = b'B' * 8192
        sent = 0
        while sent < total_bytes:
            to_send = min(len(chunk), total_bytes - sent)
            self.wfile.write(chunk[:to_send])
            sent += to_send

    def log_message(self, format, *args):
        pass # Quiet mode

if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8888
    server = HTTPServer(('127.0.0.1', port), ControlledHandler)
    server.serve_forever()

