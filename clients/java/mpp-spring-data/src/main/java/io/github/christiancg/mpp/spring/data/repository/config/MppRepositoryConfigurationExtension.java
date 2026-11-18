package io.github.christiancg.mpp.spring.data.repository.config;

import io.github.christiancg.mpp.spring.data.mapping.MppEntity;
import io.github.christiancg.mpp.spring.data.repository.MppRepository;
import io.github.christiancg.mpp.spring.data.repository.support.MppRepositoryFactoryBean;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.data.repository.config.RepositoryConfigurationExtensionSupport;
import org.springframework.data.repository.config.RepositoryConfigurationSource;

public class MppRepositoryConfigurationExtension extends RepositoryConfigurationExtensionSupport {
    public static final String DEFAULT_TEMPLATE_REF = "mppTemplate";
    private static final String TEMPLATE_REF = "templateRef";

    @Override
    public String getModuleName() {
        return "MPP";
    }

    @Override
    protected String getModulePrefix() {
        return "mpp";
    }

    @Override
    public String getRepositoryFactoryBeanClassName() {
        return MppRepositoryFactoryBean.class.getName();
    }

    @Override
    protected Collection<Class<? extends Annotation>> getIdentifyingAnnotations() {
        return List.of(MppEntity.class);
    }

    @Override
    protected Collection<Class<?>> getIdentifyingTypes() {
        return List.of(MppRepository.class);
    }

    @Override
    public void postProcess(BeanDefinitionBuilder builder, RepositoryConfigurationSource source) {
        builder.addPropertyReference("mppOperations", source.getAttribute(TEMPLATE_REF).orElse(DEFAULT_TEMPLATE_REF));
    }
}
