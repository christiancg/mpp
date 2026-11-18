package io.github.christiancg.mpp.spring.data.repository.support;

import io.github.christiancg.mpp.spring.data.core.MppOperations;
import io.github.christiancg.mpp.spring.data.core.MppQuerySpec;
import io.github.christiancg.mpp.spring.data.repository.MppRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;

public class SimpleMppRepository<T> implements MppRepository<T, String> {
    private final MppEntityInformation<T> information;
    private final MppOperations operations;

    public SimpleMppRepository(MppEntityInformation<T> information, MppOperations operations) {
        this.information = information;
        this.operations = operations;
    }

    @Override
    public <S extends T> S save(S entity) {
        return operations.save(entity);
    }

    @Override
    public <S extends T> List<S> saveAll(Iterable<S> entities) {
        return operations.saveAll(entities);
    }

    @Override
    public Optional<T> findById(String id) {
        return operations.findById(id, type());
    }

    @Override
    public boolean existsById(String id) {
        return operations.existsById(id, type());
    }

    @Override
    public List<T> findAll() {
        return operations.findAll(type());
    }

    @Override
    public List<T> findAllById(Iterable<String> ids) {
        return operations.findAllById(ids, type());
    }

    @Override
    public long count() {
        return operations.count(type());
    }

    @Override
    public void deleteById(String id) {
        operations.deleteById(id, type());
    }

    @Override
    public void delete(T entity) {
        operations.delete(entity);
    }

    @Override
    public void deleteAllById(Iterable<? extends String> ids) {
        operations.deleteAllById(StreamSupport.stream(ids.spliterator(), false).map(String.class::cast).toList(),
                type());
    }

    @Override
    public void deleteAll(Iterable<? extends T> entities) {
        entities.forEach(operations::delete);
    }

    @Override
    public void deleteAll() {
        operations.deleteAll(type());
    }

    @Override
    public List<T> findAll(Sort sort) {
        return operations.findAll(type(), sort);
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        var spec = MppQuerySpec.all().sorted(pageable.getSort());
        if (pageable.isPaged()) {
            spec = spec.page(pageable.getOffset(), pageable.getPageSize());
        }
        final var content = operations.find(type(), spec);
        return PageableExecutionUtils.getPage(content, pageable, () -> operations.count(type()));
    }

    private Class<T> type() {
        return information.getJavaType();
    }
}
