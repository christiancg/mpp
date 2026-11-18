package example;

import io.github.christiancg.mpp.spring.data.mapping.MppEntity;
import java.time.LocalDateTime;

@MppEntity(collection = "orders")
public record Order(String id, String status, double total, LocalDateTime placedAt) {
}
