package org.ccg.mpp.http;

public record HttpLimits(int maxConnections, long maxRequestBytes, long readTimeoutMs, long idleTimeoutMs) {
}
