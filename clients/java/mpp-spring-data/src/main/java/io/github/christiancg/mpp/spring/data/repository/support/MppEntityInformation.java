package io.github.christiancg.mpp.spring.data.repository.support;

import io.github.christiancg.mpp.spring.data.mapping.MppPersistentEntity;
import org.springframework.data.repository.core.support.PersistentEntityInformation;

public class MppEntityInformation<T> extends PersistentEntityInformation<T, String> {
    private final MppPersistentEntity<T> entity;

    public MppEntityInformation(MppPersistentEntity<T> entity) {
        super(entity);
        this.entity = entity;
    }

    public String collection() {
        return entity.collection();
    }

    public String project() {
        return entity.project();
    }
}
