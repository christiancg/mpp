package org.ccg.mpp.http;

@FunctionalInterface
public interface HttpHandler {
    HttpResponse handle(HttpRequest request);
}
