Stores a `double[]` property as a `#vector(...)` value:

```java
public record Article(String id, @MppVector double[] embedding) {
}
```
