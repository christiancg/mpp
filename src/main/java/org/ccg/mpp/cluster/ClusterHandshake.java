package org.ccg.mpp.cluster;

import java.io.IOException;
import java.net.SocketTimeoutException;
import org.ccg.mpp.cluster.msg.ClusterMessage;
import org.ccg.mpp.cluster.msg.ClusterMessageType;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.conn.BoundedLineReader;
import org.ccg.mpp.conn.ConnectionLog;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ex.RequestTooLargeException;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

final class ClusterHandshake {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final Logger logger = Logger.logFor(ClusterHandshake.class);

    private ClusterHandshake() {
    }

    static boolean accept(BoundedLineReader reader) throws IOException {
        final String line;
        try {
            line = firstLine(reader);
        } catch (SocketTimeoutException | RequestTooLargeException refused) {
            return rejected();
        }
        if (line == null) {
            return false;
        }
        return isHello(line) || rejected();
    }

    private static String firstLine(BoundedLineReader reader) throws IOException {
        final var deadline = System.currentTimeMillis() + clusterConfig.handshakeTimeoutMs();
        while (true) {
            final var remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new SocketTimeoutException("The cluster handshake deadline expired");
            }
            final var line = reader.readLine(remaining, Globals.CLUSTER_HANDSHAKE_MAX_LINE_BYTES);
            if (line == null || !line.isBlank()) {
                return line;
            }
        }
    }

    private static boolean isHello(String line) {
        final ClusterMessage hello;
        try {
            hello = eJson.fromJson(line, ClusterMessage.class, Globals.MAX_REQUEST_NESTING_DEPTH);
        } catch (RuntimeException unparseable) {
            return false;
        }
        return hello != null && hello.getType() == ClusterMessageType.HELLO && ClusterSecret.matches(hello.getSecret());
    }

    private static boolean rejected() {
        ConnectionLog.warn(logger, "Rejected cluster connection: handshake failed");
        return false;
    }
}
