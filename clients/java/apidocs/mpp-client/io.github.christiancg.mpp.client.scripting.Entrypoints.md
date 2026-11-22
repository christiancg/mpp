The HTTP entrypoints of one project, each routing an HTTP method and path to a stored procedure. Obtained from
`Project.entrypoints()`.

## Entrypoints(ClientContext, String)

Internal: use `Project.entrypoints()`.

@param context the client's internal context
@param project the project

## save(EntrypointDefinition)

@param entrypoint the entrypoint
@return its new version, and the version of the procedure it runs

## delete(String)

@param name the entrypoint; a procedure it created inline stays

## list()

@return the entrypoints
