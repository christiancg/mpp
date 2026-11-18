package org.ccg.mpp.unit.conn.tls;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import javax.net.ssl.SSLServerSocket;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.conn.MessageProcessor;
import org.ccg.mpp.conn.tls.SelfSignedCertificateGenerator;
import org.ccg.mpp.conn.tls.TlsContextFactory;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TlsHandshakeDeadlineTest {
    private static final long AUTH_TIMEOUT_MS = 400L;
    private static final long FINISH_WITHIN_MS = 6_000L;
    private final Configuration config = Configuration.getInstance();
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private SSLServerSocket server;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        TestUtils.setPrivateField(config, "authTimeoutMs", AUTH_TIMEOUT_MS);
        final var keystore = tempDir.resolve("mpp.p12");
        SelfSignedCertificateGenerator.generate(keystore, "change_it".toCharArray(), "mpp");
        final var tlsConfig = mock(Configuration.class);
        when(tlsConfig.getTlsKeystorePath()).thenReturn(keystore.toString());
        when(tlsConfig.getTlsKeystorePassword()).thenReturn("change_it");
        server = (SSLServerSocket) TlsContextFactory.createServerSocketFactory(tlsConfig).createServerSocket(0);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        TestUtils.setPrivateField(config, "authTimeoutMs", ConfigKey.AUTH_TIMEOUT_MS.defaultValue());
        TestUtils.setPrivateField(config, "maxConnections", ConfigKey.MAX_CONNECTIONS.defaultValue());
    }

    private Thread serveOne() throws IOException {
        final var accepted = server.accept();
        final var thread = Thread.ofVirtual().start(new MessageProcessor(accepted));
        assertNotNull(thread);
        return thread;
    }

    private static void awaitFinished(Thread connection) throws InterruptedException {
        connection.join(FINISH_WITHIN_MS);
        assertFalse(connection.isAlive(), "the connection outlived its handshake deadline");
    }

    @Test
    public void test_a_peer_that_never_starts_the_handshake_is_closed_at_the_deadline() throws Exception {
        try (var silent = new Socket("127.0.0.1", server.getLocalPort())) {
            awaitFinished(serveOne());
            assertFalse(silent.isClosed());
        }
    }

    @Test
    @SuppressWarnings("BusyWait")
    public void test_a_dripped_client_hello_is_closed_at_the_deadline() throws Exception {
        try (var dripping = new Socket("127.0.0.1", server.getLocalPort())) {
            final var out = dripping.getOutputStream();
            out.write(new byte[]{0x16, 0x03, 0x01, 0x01, (byte) 0xF0});
            out.flush();
            final var drip = Thread.ofVirtual().start(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(100);
                        out.write(0x01);
                        out.flush();
                    }
                } catch (IOException | InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                awaitFinished(serveOne());
            } finally {
                drip.interrupt();
            }
        }
    }

    @Test
    public void test_a_connection_refused_at_max_connections_does_not_block_on_its_handshake() throws Exception {
        TestUtils.setPrivateField(config, "maxConnections", 1);
        final var occupant = clientTracker.addClient(TestUtils.clientSocket());
        try (var _ = new Socket("127.0.0.1", server.getLocalPort())) {
            awaitFinished(serveOne());
        } finally {
            clientTracker.removeById(occupant);
        }
    }
}
