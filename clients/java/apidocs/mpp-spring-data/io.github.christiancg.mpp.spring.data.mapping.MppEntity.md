Marks a class stored in MPP and names where. Optional: an unannotated class is stored in the
collection named after the class (`Order` becomes `order`) in the default project.

```java
@MppEntity(collection = "orders")
public record Order(@Id String orderId, String status, double total) {
}
```

## collection()

@return the collection, or empty for the uncapitalised class name

## project()

@return the project, or empty for the default project (`mpp.project`)
