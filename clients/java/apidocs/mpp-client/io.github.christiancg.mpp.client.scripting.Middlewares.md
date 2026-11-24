The HTTP middlewares of one project, each wrapping the entrypoints it matches with a stored procedure that runs before
or after them. Obtained from `Project.middlewares()`.

## Middlewares(ClientContext, String)

Internal: use `Project.middlewares()`.

@param context the client's internal context
@param project the project

## save(MiddlewareDefinition)

@param middleware the middleware
@return its new version, and the version of the procedure it runs

## delete(String)

@param name the middleware; its procedure stays

## list()

@return the middlewares in execution order: ingress before egress, then by order, then by name
