import http.client
import json
import socket
import ssl


class Reply:
    def __init__(self, status: int, headers: dict, body: bytes):
        self.status = status
        self.headers = {key.lower(): value for key, value in headers.items()}
        self.body = body

    def json(self):
        return json.loads(self.body.decode("utf-8"))

    def text(self) -> str:
        return self.body.decode("utf-8")


def unverified_context() -> ssl.SSLContext:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context


def read_until_closed(sock: socket.socket, timeout: float = 10.0) -> bytes:
    sock.settimeout(timeout)
    chunks = []
    try:
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                break
            chunks.append(chunk)
    except (ConnectionResetError, socket.timeout):
        pass
    return b"".join(chunks)


class HttpTarget:
    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port

    def https_connection(self) -> http.client.HTTPSConnection:
        return http.client.HTTPSConnection(self.host, self.port, timeout=15, context=unverified_context())

    def raw_socket(self) -> socket.socket:
        return socket.create_connection((self.host, self.port), timeout=10)

    def port_open(self) -> bool:
        try:
            with socket.create_connection((self.host, self.port), timeout=1):
                return True
        except OSError:
            return False

    def request(self, method: str, path: str, body=None, headers=None, tls: bool = False) -> Reply:
        connection = self.https_connection() if tls else http.client.HTTPConnection(self.host, self.port,
                                                                                     timeout=15)
        try:
            payload = json.dumps(body).encode("utf-8") if isinstance(body, (dict, list)) else body
            all_headers = dict(headers or {})
            if isinstance(body, (dict, list)):
                all_headers.setdefault("Content-Type", "application/json")
            connection.request(method, path, body=payload, headers=all_headers)
            response = connection.getresponse()
            return Reply(response.status, dict(response.getheaders()), response.read())
        finally:
            connection.close()
