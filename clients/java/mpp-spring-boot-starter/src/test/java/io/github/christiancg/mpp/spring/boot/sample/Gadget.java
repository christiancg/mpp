package io.github.christiancg.mpp.spring.boot.sample;

import io.github.christiancg.mpp.spring.data.mapping.MppEntity;

@MppEntity(collection = "gadgets")
public record Gadget(String id, String name) {
}
