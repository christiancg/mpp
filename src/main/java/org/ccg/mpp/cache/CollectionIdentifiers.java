package org.ccg.mpp.cache;

import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.NameMemo;

public final class CollectionIdentifiers {
    static final int MAX_REMEMBERED = 8192;
    private static final NameMemo<CollectionNames> MEMO = new NameMemo<>(MAX_REMEMBERED);

    private CollectionIdentifiers() {
    }

    static String of(String projectName, String collName) {
        return namesOf(projectName, collName).identifier();
    }

    static CollectionNames namesOf(String projectName, String collName) {
        final var hit = MEMO.get(projectName, collName);
        if (hit != null) {
            return hit;
        }
        final var names = new CollectionNames(projectName, collName, concatenated(projectName, collName));
        return projectName == null || collName == null ? names : MEMO.remember(projectName, collName, names);
    }

    public static void forget(String projectName, String collName) {
        MEMO.forget(projectName, collName);
    }

    static void forgetProject(String projectName) {
        MEMO.forgetAll(projectName);
    }

    private static String concatenated(String projectName, String collName) {
        return projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }
}
