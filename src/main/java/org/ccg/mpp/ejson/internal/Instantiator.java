package org.ccg.mpp.ejson.internal;

import org.ccg.mpp.ejson.elements.JsonObject;

@FunctionalInterface
public interface Instantiator {
    Object newInstance(JsonObject parsed) throws Exception;
}
