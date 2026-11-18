package org.ccg.mpp.conn;

import org.ccg.mpp.config.Globals;
import org.ccg.mpp.log.LogRateLimiter;
import org.ccg.mpp.log.Logger;

public final class ConnectionLog {
    private static final LogRateLimiter LIMITER = new LogRateLimiter(Globals.LOG_RATE_LIMIT_PER_SECOND);

    private ConnectionLog() {
    }

    public static void warn(Logger logger, String message) {
        final var entry = LIMITER.admit(message);
        if (entry != null) {
            logger.warning(entry);
        }
    }
}
