package io.github.christiancg.mpp.spring.boot;

import io.github.christiancg.mpp.spring.data.repository.EnableMppRepositories;
import io.github.christiancg.mpp.spring.data.repository.config.MppRepositoryConfigurationExtension;
import java.lang.annotation.Annotation;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.data.AbstractRepositoryConfigurationSourceSupport;
import org.springframework.data.repository.config.RepositoryConfigurationExtension;

@NullMarked
class MppRepositoriesAutoConfigureRegistrar extends AbstractRepositoryConfigurationSourceSupport {
    @Override
    protected Class<? extends Annotation> getAnnotation() {
        return EnableMppRepositories.class;
    }

    @Override
    protected Class<?> getConfiguration() {
        return EnableMppRepositoriesConfiguration.class;
    }

    @Override
    protected RepositoryConfigurationExtension getRepositoryConfigurationExtension() {
        return new MppRepositoryConfigurationExtension();
    }

    @EnableMppRepositories
    private static final class EnableMppRepositoriesConfiguration {
    }
}
