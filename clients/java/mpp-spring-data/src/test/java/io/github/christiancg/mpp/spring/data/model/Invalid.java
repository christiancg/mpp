package io.github.christiancg.mpp.spring.data.model;

import io.github.christiancg.mpp.spring.data.mapping.MppEntity;
import org.springframework.data.annotation.Id;

public interface Invalid {
    record NumericId(@Id long key) {
    }

    record TwoIds(@Id String key, String id) {
    }

    record NoId(String name) {
    }

    @MppEntity(collection = "x", project = "shop")
    record ShortCollection(String id) {
    }

    record NoProject(String id) {
    }
}
