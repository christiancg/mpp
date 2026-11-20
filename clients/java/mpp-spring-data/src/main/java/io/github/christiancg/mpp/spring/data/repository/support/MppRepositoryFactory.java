package io.github.christiancg.mpp.spring.data.repository.support;

import io.github.christiancg.mpp.spring.data.core.MppOperations;
import io.github.christiancg.mpp.spring.data.mapping.MppPersistentEntity;
import io.github.christiancg.mpp.spring.data.repository.query.MppQueryLookupStrategy;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.ValueExpressionDelegate;

public class MppRepositoryFactory extends RepositoryFactorySupport {
    private final MppOperations operations;

    public MppRepositoryFactory(MppOperations operations) {
        this.operations = operations;
    }

    @Override
    public EntityInformation<?, ?> getEntityInformation(RepositoryMetadata metadata) {
        return information(metadata.getDomainType());
    }

    @Override
    protected Object getTargetRepository(RepositoryInformation metadata) {
        return getTargetRepositoryViaReflection(metadata, information(metadata.getDomainType()), operations);
    }

    @Override
    protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
        return SimpleMppRepository.class;
    }

    @Override
    protected Optional<QueryLookupStrategy> getQueryLookupStrategy(QueryLookupStrategy.@Nullable Key key,
            ValueExpressionDelegate delegate) {
        return Optional.of(new MppQueryLookupStrategy(operations));
    }

    @Override
    protected void validate(RepositoryMetadata metadata) {
        super.validate(metadata);
        if (metadata.getIdType() != String.class) {
            throw new MappingException(metadata.getRepositoryInterface().getName() + " declares a "
                    + metadata.getIdType().getSimpleName() + " id; MPP ids are strings");
        }
    }

    private <T> MppEntityInformation<T> information(Class<T> type) {
        @SuppressWarnings("unchecked")
        final var entity = (MppPersistentEntity<T>) operations.getMappingContext().getRequiredPersistentEntity(type);
        entity.requiredIdProperty();
        entity.collection();
        entity.project();
        return new MppEntityInformation<>(entity);
    }
}
