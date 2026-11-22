# Entrypoints

The HTTP entrypoints of one project: each maps an HTTP method and path to a stored procedure, served on the server's
`httpPort` at `/{project}{path}`.

## save

Creates or replaces an entrypoint.

@param definition the entrypoint; exactly one of `procedureName` and `procedure`
@returns its new version and the version of the procedure it runs

## delete

Deletes an entrypoint. Deleting one that does not exist succeeds. An inline procedure stays saved.

@param name the entrypoint to delete

## list

@returns the entrypoints
