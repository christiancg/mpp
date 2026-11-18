package io.github.christiancg.mpp.spring.boot;

import io.github.christiancg.mpp.client.ClientSettings;

@FunctionalInterface
public interface MppClientSettingsCustomizer {
    void customize(ClientSettings.Builder builder);
}
