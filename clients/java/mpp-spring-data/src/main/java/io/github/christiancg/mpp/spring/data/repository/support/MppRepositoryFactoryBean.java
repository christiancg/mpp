package io.github.christiancg.mpp.spring.data.repository.support;

import io.github.christiancg.mpp.spring.data.core.MppOperations;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;

public class MppRepositoryFactoryBean<T extends Repository<S, String>, S>
        extends
            RepositoryFactoryBeanSupport<T, S, String> {
    private @Nullable MppOperations operations;

    public MppRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
    }

    @SuppressWarnings("unused")
    public void setMppOperations(MppOperations operations) {
        this.operations = operations;
        setMappingContext(operations.getMappingContext());
    }

    @Override
    protected RepositoryFactorySupport createRepositoryFactory() {
        return new MppRepositoryFactory(
                Objects.requireNonNull(operations, "an MppOperations bean is required (templateRef)"));
    }

    @Override
    public void afterPropertiesSet() {
        Objects.requireNonNull(operations, "an MppOperations bean is required (templateRef)");
        super.afterPropertiesSet();
    }
}
