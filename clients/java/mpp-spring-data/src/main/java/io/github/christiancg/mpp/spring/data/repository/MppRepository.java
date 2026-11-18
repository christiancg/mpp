package io.github.christiancg.mpp.spring.data.repository;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.ListPagingAndSortingRepository;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface MppRepository<T, I> extends ListCrudRepository<T, I>, ListPagingAndSortingRepository<T, I> {
}
