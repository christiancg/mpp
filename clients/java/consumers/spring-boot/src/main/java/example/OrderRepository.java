package example;

import io.github.christiancg.mpp.spring.data.repository.MppRepository;
import java.util.List;

public interface OrderRepository extends MppRepository<Order, String> {
    List<Order> findByStatusAndTotalGreaterThanOrderByTotalDesc(String status, double total);
}
