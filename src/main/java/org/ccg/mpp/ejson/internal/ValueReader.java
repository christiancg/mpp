package org.ccg.mpp.ejson.internal;

import org.ccg.mpp.ejson.elements.JsonBaseElement;

@FunctionalInterface
public interface ValueReader {
    Object read(JsonBaseElement element) throws Exception;
}
