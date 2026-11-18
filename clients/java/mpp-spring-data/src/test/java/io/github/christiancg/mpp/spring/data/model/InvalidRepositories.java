package io.github.christiancg.mpp.spring.data.model;

import io.github.christiancg.mpp.spring.data.repository.MppRepository;
import java.util.List;

@SuppressWarnings("unused")
public interface InvalidRepositories {
    interface LikeRepository extends MppRepository<Widget, String> {
        List<Widget> findByNameLike(String pattern);
    }

    interface TwoOrdersRepository extends MppRepository<Widget, String> {
        List<Widget> findByNameOrderBySizeAscNameDesc(String name);
    }

    interface IdIgnoreCaseRepository extends MppRepository<Widget, String> {
        List<Widget> findByCodeIgnoreCase(String code);
    }

    interface TransientRepository extends MppRepository<Widget, String> {
        List<Widget> findByScratch(String value);
    }

    interface NumericIdRepository extends MppRepository<Widget, Long> {
    }
}
