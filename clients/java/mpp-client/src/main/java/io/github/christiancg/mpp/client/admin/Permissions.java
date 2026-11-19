package io.github.christiancg.mpp.client.admin;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class Permissions {
    public static final char COLLECTION_SEPARATOR = '|';

    private final boolean admin;
    private final Set<GlobalPermission> global;
    private final Map<String, DataPermission> projects;
    private final Map<String, DataPermission> collections;
    private final Map<String, ScriptPermission> scripts;

    private Permissions(Builder builder) {
        this.admin = builder.admin;
        this.global = Collections.unmodifiableSet(EnumSet.copyOf(builder.global));
        this.projects = Collections.unmodifiableMap(new LinkedHashMap<>(builder.projects));
        this.collections = Collections.unmodifiableMap(new LinkedHashMap<>(builder.collections));
        this.scripts = Collections.unmodifiableMap(new LinkedHashMap<>(builder.scripts));
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean admin() {
        return admin;
    }

    public Set<GlobalPermission> global() {
        return global;
    }

    public Map<String, DataPermission> projects() {
        return projects;
    }

    public Map<String, DataPermission> collections() {
        return collections;
    }

    public Map<String, ScriptPermission> scripts() {
        return scripts;
    }

    public static final class Builder {
        private final Set<GlobalPermission> global = EnumSet.noneOf(GlobalPermission.class);
        private final Map<String, DataPermission> projects = new LinkedHashMap<>();
        private final Map<String, DataPermission> collections = new LinkedHashMap<>();
        private final Map<String, ScriptPermission> scripts = new LinkedHashMap<>();
        private boolean admin;

        private Builder() {
        }

        public Builder admin(boolean admin) {
            this.admin = admin;
            return this;
        }

        public Builder global(GlobalPermission... permissions) {
            global.addAll(Arrays.asList(permissions));
            return this;
        }

        public Builder project(String project, DataPermission permission) {
            projects.put(Objects.requireNonNull(project, "project"), Objects.requireNonNull(permission, "permission"));
            return this;
        }

        public Builder collection(String project, String collection, DataPermission permission) {
            collections.put(
                    Objects.requireNonNull(project, "project") + COLLECTION_SEPARATOR
                            + Objects.requireNonNull(collection, "collection"),
                    Objects.requireNonNull(permission, "permission"));
            return this;
        }

        public Builder scripts(String project, ScriptPermission permission) {
            scripts.put(Objects.requireNonNull(project, "project"), Objects.requireNonNull(permission, "permission"));
            return this;
        }

        public Permissions build() {
            return new Permissions(this);
        }
    }
}
