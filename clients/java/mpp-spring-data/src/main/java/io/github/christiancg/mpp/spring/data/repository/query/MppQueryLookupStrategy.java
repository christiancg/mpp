package io.github.christiancg.mpp.spring.data.repository.query;

import io.github.christiancg.mpp.spring.data.core.MppOperations;
import java.lang.reflect.Method;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.repository.core.NamedQueries;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.query.DefaultParameters;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.RepositoryQuery;

public class MppQueryLookupStrategy implements QueryLookupStrategy {
    private final MppOperations operations;

    public MppQueryLookupStrategy(MppOperations operations) {
        this.operations = operations;
    }

    @Override
    public RepositoryQuery resolveQuery(Method method, RepositoryMetadata metadata, ProjectionFactory factory,
            NamedQueries namedQueries) {
        return new PartTreeMppQuery(new QueryMethod(method, metadata, factory, DefaultParameters::new), operations);
    }
}
