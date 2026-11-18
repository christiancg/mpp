package io.github.christiancg.mpp.spring.data.mapping;

import io.github.christiancg.mpp.client.Document;
import io.github.christiancg.mpp.client.values.GeoPoint;
import io.github.christiancg.mpp.client.values.Vector;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.data.core.TypeInformation;
import org.springframework.data.mapping.context.AbstractMappingContext;
import org.springframework.data.mapping.model.Property;
import org.springframework.data.mapping.model.SimpleTypeHolder;

public class MppMappingContext extends AbstractMappingContext<MppPersistentEntity<?>, MppPersistentProperty> {
    private static final Set<Class<?>> STORED_AS_VALUES = Set.of(Document.class, GeoPoint.class, Vector.class,
            LocalDateTime.class, LocalTime.class, Instant.class, OffsetDateTime.class, ZonedDateTime.class);

    private final @Nullable String defaultDatabase;

    public MppMappingContext(@Nullable String defaultDatabase) {
        this.defaultDatabase = defaultDatabase;
        setSimpleTypeHolder(new SimpleTypeHolder(STORED_AS_VALUES, true));
    }

    public @Nullable String defaultDatabase() {
        return defaultDatabase;
    }

    @Override
    protected <T> MppPersistentEntity<T> createPersistentEntity(TypeInformation<T> typeInformation) {
        return new MppPersistentEntity<>(typeInformation, defaultDatabase);
    }

    @Override
    protected MppPersistentProperty createPersistentProperty(Property property, MppPersistentEntity<?> owner,
            SimpleTypeHolder simpleTypeHolder) {
        return new MppPersistentProperty(property, owner, simpleTypeHolder);
    }
}
