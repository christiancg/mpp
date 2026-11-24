# Middlewares

The HTTP middlewares of one project: each wraps the entrypoints its match selects with a stored procedure that runs
before (`INGRESS`) or after (`EGRESS`) the entrypoint's own.

## save

Creates or replaces a middleware.

@param definition the middleware; exactly one of `procedureName` and `procedure`
@returns its new version and the version of the procedure it runs

## delete

Deletes a middleware. Deleting one that does not exist succeeds. Its procedure stays saved.

@param name the middleware to delete

## list

@returns the middlewares, in execution order
