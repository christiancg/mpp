package org.ccg.mpp.simplejs.host;

import java.util.UUID;

interface ScriptSession {
    UUID sessionClientId();

    void assertSessionThread();
}
