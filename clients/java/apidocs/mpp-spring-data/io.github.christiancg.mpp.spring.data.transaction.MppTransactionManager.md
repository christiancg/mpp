Runs `@Transactional` methods in an MPP transaction: one connection held from begin to commit or
rollback, with every template and repository call on that thread routed to it. `NESTED` is not supported; `REQUIRES_NEW`
uses a second connection. The server rolls back a transaction idle for `transactionIdleTimeoutMs` (30 s by default).

## MppTransactionManager(MppClient)

@param client the client whose connections transactions use
