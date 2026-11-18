package org.ccg.mpp.unit.conn.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.conn.tls.SelfSignedCertificateGenerator;
import org.ccg.mpp.conn.tls.TlsContextFactory;
import org.ccg.mpp.ex.TlsConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TlsContextFactoryTest {

    private static Configuration mockConfig(Path keystorePath, String password) {
        final var config = mock(Configuration.class);
        when(config.getTlsKeystorePath()).thenReturn(keystorePath.toString());
        when(config.getTlsKeystorePassword()).thenReturn(password);
        return config;
    }

    @Test
    public void test_generates_keystore_when_absent(@TempDir Path tempDir) {
        final var keystorePath = tempDir.resolve("certs").resolve("mpp.p12");
        final var config = mockConfig(keystorePath, "change_it");

        final var factory = TlsContextFactory.createServerSocketFactory(config);

        assertNotNull(factory);
        assertTrue(Files.exists(keystorePath), "a self-signed keystore should have been generated");
    }

    @Test
    public void test_loads_existing_keystore(@TempDir Path tempDir) {
        final var keystorePath = tempDir.resolve("mpp.p12");
        SelfSignedCertificateGenerator.generate(keystorePath, "change_it".toCharArray(), "mpp");
        final var config = mockConfig(keystorePath, "change_it");

        final var factory = TlsContextFactory.createServerSocketFactory(config);

        assertNotNull(factory);
    }

    @Test
    public void test_wrong_password_throws(@TempDir Path tempDir) {
        final var keystorePath = tempDir.resolve("mpp.p12");
        SelfSignedCertificateGenerator.generate(keystorePath, "change_it".toCharArray(), "mpp");
        final var config = mockConfig(keystorePath, "wrong-password");

        assertThrows(TlsConfigurationException.class, () -> TlsContextFactory.createServerSocketFactory(config));
    }

    @Test
    public void clusterServerFactoryTrustsTheSharedKeystore(@TempDir Path tempDir) throws Exception {
        final var keystorePath = tempDir.resolve("cluster.p12");
        SelfSignedCertificateGenerator.generate(keystorePath, "change_it".toCharArray(), "mpp");
        final var config = mockConfig(keystorePath, "change_it");
        final var peerCertificates = new AtomicInteger();
        final var failure = new AtomicReference<Exception>();
        try (var server = (SSLServerSocket) TlsContextFactory.createClusterServerSocketFactory(config)
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setNeedClientAuth(true);
            final var acceptor = Thread.ofVirtual().start(() -> {
                try (var accepted = (SSLSocket) server.accept()) {
                    accepted.startHandshake();
                    peerCertificates.set(accepted.getSession().getPeerCertificates().length);
                } catch (Exception e) {
                    failure.set(e);
                }
            });
            try (var client = (SSLSocket) TlsContextFactory.createSocketFactory(config)
                    .createSocket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.startHandshake();
                acceptor.join(10_000);
            }
        }
        assertNull(failure.get());
        assertEquals(1, peerCertificates.get());
    }
}
