package io.github.christiancg.mpp.spring.data.repository.config;

import io.github.christiancg.mpp.spring.data.repository.EnableMppRepositories;
import java.lang.annotation.Annotation;
import org.springframework.data.repository.config.RepositoryBeanDefinitionRegistrarSupport;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;

public class MppRepositoriesRegistrar extends RepositoryBeanDefinitionRegistrarSupport {
    @Override
    protected Class<? extends Annotation> getAnnotation() {
        return EnableMppRepositories.class;
    }

    @Override
    protected RepositoryConfigurationExtension getExtension() {
        return new MppRepositoryConfigurationExtension();
    }
}
