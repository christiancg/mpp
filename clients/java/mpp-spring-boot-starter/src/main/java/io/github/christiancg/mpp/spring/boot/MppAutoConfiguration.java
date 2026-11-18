package io.github.christiancg.mpp.spring.boot;

import io.github.christiancg.mpp.client.ClientSettings;
import io.github.christiancg.mpp.client.MppClient;
import io.github.christiancg.mpp.client.error.MppException;
import io.github.christiancg.mpp.spring.data.core.MppExceptionTranslator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration(afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnClass(MppClient.class)
@EnableConfigurationProperties(MppProperties.class)
public class MppAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ClientSettings mppClientSettings(MppProperties properties, ObjectProvider<JsonMapper> objectMapper,
            ObjectProvider<MppClientSettingsCustomizer> customizers) {
        final var builder = properties.toSettings(objectMapper.getIfUnique());
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public MppClient mppClient(ClientSettings settings) {
        try {
            return MppClient.connect(settings);
        } catch (MppException e) {
            throw new MppExceptionTranslator().translate(e);
        }
    }
}
