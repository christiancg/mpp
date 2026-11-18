The `mpp.*` properties.

```properties
mpp.hosts=db1:8989,db2:8989
mpp.username=app
mpp.password=${MPP_PASSWORD}
mpp.database=shop
mpp.pool-size=16
mpp.tls.enabled=true
mpp.tls.trust-store=/etc/mpp/trust.p12
```

@param hosts servers as `host:port` (`localhost:8989` by default)
@param username the user to authenticate as
@param password the password
@param database the database of entities that name none
@param poolSize pooled connections
@param connectTimeout socket and TLS handshake timeout
@param requestTimeout per-request timeout
@param acquireTimeout wait for a free pooled connection
@param shutdownTimeout wait for borrowed connections at shutdown
@param maxReplyBytes the largest reply read
@param temporalZone the zone zoned date-times are converted to
@param tls TLS settings
@param retry retry settings

## toSettings(JsonMapper)

@param objectMapper Boot's Jackson mapper, used as the base of POJO mapping, or null for the client's own
@return a settings builder holding these properties
