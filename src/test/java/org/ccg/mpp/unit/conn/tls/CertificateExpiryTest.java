package org.ccg.mpp.unit.conn.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.ccg.mpp.conn.tls.CertificateExpiry;
import org.ccg.mpp.conn.tls.SelfSignedCertificateGenerator;
import org.ccg.mpp.log.LogWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CertificateExpiryTest {
    private static final char[] PASSWORD = "expiry-pass".toCharArray();

    private static Instant notAfterOf(KeyStore keyStore) throws Exception {
        return ((X509Certificate) keyStore.getCertificate("mpp")).getNotAfter().toInstant();
    }

    private static KeyStore keyStoreExpiringAt(Instant notAfter) throws Exception {
        final var certificate = mock(X509Certificate.class);
        when(certificate.getNotAfter()).thenReturn(Date.from(notAfter));
        final var keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, PASSWORD);
        keyStore.setCertificateEntry("soon", certificate);
        return keyStore;
    }

    @Test
    public void test_a_fresh_certificate_is_not_reported(@TempDir Path dir) throws Exception {
        final var keyStore = SelfSignedCertificateGenerator.generate(dir.resolve("k.p12"), PASSWORD, "mpp");
        assertTrue(CertificateExpiry.expiringWithinWindow(keyStore, Instant.now()).isEmpty());
    }

    @Test
    public void test_a_certificate_inside_the_window_is_reported(@TempDir Path dir) throws Exception {
        final var keyStore = SelfSignedCertificateGenerator.generate(dir.resolve("k.p12"), PASSWORD, "mpp");
        final var notAfter = notAfterOf(keyStore);
        final var notices = CertificateExpiry.expiringWithinWindow(keyStore, notAfter.minus(Duration.ofDays(10)));
        assertEquals(1, notices.size());
        assertTrue(notices.getFirst().contains("'mpp' expires at"), notices.getFirst());
        assertTrue(CertificateExpiry
                .expiringWithinWindow(keyStore, notAfter.minus(CertificateExpiry.WARNING_WINDOW).minusSeconds(60))
                .isEmpty());
    }

    @Test
    public void test_an_expired_certificate_is_reported(@TempDir Path dir) throws Exception {
        final var keyStore = SelfSignedCertificateGenerator.generate(dir.resolve("k.p12"), PASSWORD, "mpp");
        final var notices = CertificateExpiry.expiringWithinWindow(keyStore, notAfterOf(keyStore).plusSeconds(60));
        assertTrue(notices.getFirst().contains("'mpp' expired at"), notices.getFirst());
    }

    @Test
    public void test_a_near_expiry_cert_warns() throws Exception {
        final var keyStore = keyStoreExpiringAt(Instant.now().plus(Duration.ofDays(3)));
        final var keystorePath = Path.of("certs", "mpp.p12");
        try (var logWriter = mockStatic(LogWriter.class)) {
            CertificateExpiry.warnIfExpiringSoon(keyStore, keystorePath);
            logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains("SECURITY WARNING")
                    && entry.contains("'soon' expires at") && entry.contains(keystorePath.toString()))));
        }
    }

    @Test
    public void test_an_unreadable_keystore_is_reported_not_thrown() throws Exception {
        final var unloaded = KeyStore.getInstance("PKCS12");
        try (var logWriter = mockStatic(LogWriter.class)) {
            CertificateExpiry.warnIfExpiringSoon(unloaded, Path.of("certs/mpp.p12"));
            logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains("Could not read"))));
        }
    }
}
