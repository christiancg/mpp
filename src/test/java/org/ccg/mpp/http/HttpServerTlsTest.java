package org.ccg.mpp.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.conn.tls.SelfSignedCertificateGenerator;
import org.ccg.mpp.conn.tls.TlsContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HttpServerTlsTest {
    private static final String PASSWORD = "change_it";
    private static final HttpHandler HELLO = _ -> new HttpResponse(200,
            Map.of(HttpResponse.CONTENT_TYPE, HttpResponse.PLAIN_TEXT), "hello".getBytes(StandardCharsets.UTF_8),
            false);
    private HttpServer server;

    @BeforeEach
    void start(@TempDir Path tempDir) throws Exception {
        final var keystore = tempDir.resolve("mpp.p12");
        SelfSignedCertificateGenerator.generate(keystore, PASSWORD.toCharArray(), "mpp");
        final var config = mock(Configuration.class);
        when(config.getTlsKeystorePath()).thenReturn(keystore.toString());
        when(config.getTlsKeystorePassword()).thenReturn(PASSWORD);
        server = new HttpServer();
        server.start(0, "127.0.0.1", TlsContextFactory.createServerSocketFactory(config),
                List.of(TlsContextFactory.enabledProtocols()), new HttpLimits(0, 1024, 500, 2000), HELLO);
    }

    @AfterEach
    void stop() {
        server.stopAccepting();
        server.drain(1000);
    }

    private SSLSocket trustingClient(String... protocols) throws Exception {
        final var trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        final var context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustAll}, null);
        final var socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", server.boundPort());
        if (protocols.length > 0) {
            socket.setEnabledProtocols(protocols);
        }
        return socket;
    }

    @Test
    public void test_https_request_is_served_over_tls_1_3_with_keep_alive() throws Exception {
        final var socket = trustingClient();
        try (var client = new RawHttpClient(socket)) {
            client.send("GET /a HTTP/1.1\r\n\r\nGET /b HTTP/1.1\r\n\r\n");

            assertEquals("hello", client.readResponse().body());
            assertEquals("keep-alive", client.readResponse().connection());
            assertEquals("TLSv1.3", socket.getSession().getProtocol());
        }
        assertTrue(server.isTls());
    }

    @Test
    public void test_tls_1_1_is_refused() {
        assertThrows(Exception.class, () -> {
            try (var socket = trustingClient("TLSv1.1")) {
                socket.startHandshake();
            }
        });
    }

    @Test
    public void test_alpn_selects_http_1_1_when_the_client_offers_h2() throws Exception {
        try (var socket = trustingClient()) {
            final var parameters = socket.getSSLParameters();
            parameters.setApplicationProtocols(new String[]{"h2", "http/1.1"});
            socket.setSSLParameters(parameters);
            socket.startHandshake();

            assertEquals("http/1.1", socket.getApplicationProtocol());
        }
    }

    @Test
    public void test_plaintext_request_on_the_https_port_gets_no_http_reply() throws IOException {
        try (var socket = new Socket("127.0.0.1", server.boundPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET /a HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));

            assertFalse(new String(untilClosed(socket), StandardCharsets.ISO_8859_1).startsWith("HTTP/"));
        }
    }

    @Test
    public void test_stalled_handshake_is_cut_by_the_read_deadline() throws IOException {
        try (var socket = new Socket("127.0.0.1", server.boundPort())) {
            socket.setSoTimeout(10_000);

            assertTrue(untilClosed(socket).length < 64);
        }
    }

    private static byte[] untilClosed(Socket socket) throws IOException {
        try {
            return socket.getInputStream().readAllBytes();
        } catch (SocketException reset) {
            return new byte[0];
        }
    }
}
