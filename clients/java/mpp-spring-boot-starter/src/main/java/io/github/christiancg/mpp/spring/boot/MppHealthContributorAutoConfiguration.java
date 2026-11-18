package io.github.christiancg.mpp.spring.boot;

import io.github.christiancg.mpp.client.MppClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = MppAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(MppClient.class)
@ConditionalOnEnabledHealthIndicator("mpp")
public class MppHealthContributorAutoConfiguration {
    @Bean(name = "mppHealthIndicator")
    @ConditionalOnMissingBean(name = "mppHealthIndicator")
    public MppHealthIndicator mppHealthIndicator(MppClient client) {
        return new MppHealthIndicator(client);
    }
}
