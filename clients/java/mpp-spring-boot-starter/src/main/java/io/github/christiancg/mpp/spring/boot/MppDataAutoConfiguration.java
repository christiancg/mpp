package io.github.christiancg.mpp.spring.boot;

import io.github.christiancg.mpp.client.MppClient;
import io.github.christiancg.mpp.spring.data.core.MppOperations;
import io.github.christiancg.mpp.spring.data.core.MppTemplate;
import io.github.christiancg.mpp.spring.data.mapping.MppEntity;
import io.github.christiancg.mpp.spring.data.mapping.MppMappingContext;
import io.github.christiancg.mpp.spring.data.transaction.MppTransactionManager;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.util.ClassUtils;

@AutoConfiguration(after = MppAutoConfiguration.class)
@ConditionalOnClass(MppTemplate.class)
@ConditionalOnBean(MppClient.class)
@EnableTransactionManagement
public class MppDataAutoConfiguration {
    public static final String TEMPLATE_BEAN = "mppTemplate";
    public static final String TRANSACTION_MANAGER_BEAN = "mppTransactionManager";

    @Bean
    @ConditionalOnMissingBean
    public MppMappingContext mppMappingContext(MppProperties properties, BeanFactory beanFactory) {
        final var context = new MppMappingContext(properties.project());
        context.setInitialEntitySet(scanEntities(beanFactory));
        return context;
    }

    @Bean(name = TEMPLATE_BEAN)
    @ConditionalOnMissingBean(MppOperations.class)
    public MppTemplate mppTemplate(MppClient client, MppMappingContext mappingContext) {
        return new MppTemplate(client, mappingContext);
    }

    @Bean(name = TRANSACTION_MANAGER_BEAN)
    @ConditionalOnMissingBean(name = TRANSACTION_MANAGER_BEAN)
    public MppTransactionManager mppTransactionManager(MppClient client) {
        return new MppTransactionManager(client);
    }

    static Set<Class<?>> scanEntities(BeanFactory beanFactory) {
        final var entities = new HashSet<Class<?>>();
        if (!AutoConfigurationPackages.has(beanFactory)) {
            return entities;
        }
        final var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(MppEntity.class));
        for (final var basePackage : AutoConfigurationPackages.get(beanFactory)) {
            for (final var candidate : scanner.findCandidateComponents(basePackage)) {
                entities.add(ClassUtils.resolveClassName(Objects.requireNonNull(candidate.getBeanClassName()),
                        ClassUtils.getDefaultClassLoader()));
            }
        }
        return entities;
    }
}
