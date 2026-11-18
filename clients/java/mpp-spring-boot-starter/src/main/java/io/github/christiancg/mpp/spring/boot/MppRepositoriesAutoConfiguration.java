package io.github.christiancg.mpp.spring.boot;

import io.github.christiancg.mpp.spring.data.core.MppOperations;
import io.github.christiancg.mpp.spring.data.repository.MppRepository;
import io.github.christiancg.mpp.spring.data.repository.support.MppRepositoryFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.ConditionalOnRepositoryType;
import org.springframework.boot.autoconfigure.data.RepositoryType;
import org.springframework.context.annotation.Import;

@AutoConfiguration(after = MppDataAutoConfiguration.class)
@ConditionalOnClass(MppRepository.class)
@ConditionalOnBean(MppOperations.class)
@ConditionalOnMissingBean(MppRepositoryFactoryBean.class)
@ConditionalOnRepositoryType(store = "mpp", type = RepositoryType.IMPERATIVE)
@Import(MppRepositoriesAutoConfigureRegistrar.class)
public class MppRepositoriesAutoConfiguration {
}
