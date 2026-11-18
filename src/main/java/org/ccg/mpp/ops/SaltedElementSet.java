package org.ccg.mpp.ops;

import java.util.AbstractSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.utils.SaltedKey;

final class SaltedElementSet extends AbstractSet<JsonBaseElement> {
    private final Set<SaltedKey> keys = new HashSet<>();

    @Override
    public boolean add(JsonBaseElement element) {
        return keys.add(new SaltedKey(element));
    }

    @Override
    public boolean contains(Object candidate) {
        return candidate instanceof JsonBaseElement element && keys.contains(new SaltedKey(element));
    }

    boolean containsKey(SaltedKey key) {
        return keys.contains(key);
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public Iterator<JsonBaseElement> iterator() {
        return keys.stream().map(SaltedKey::element).iterator();
    }

    @Override
    public int size() {
        return keys.size();
    }
}
