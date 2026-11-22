The HTTP entrypoints of one project. Obtained from `Project.Entrypoints()`.

An entrypoint maps an HTTP method and path to a stored procedure, so a plain HTTP client can run it on the server's `httpPort`. It runs with its definer's permissions. Saving one needs the script permission `Manage`.

## DeleteAsync(string, CancellationToken)
Deletes an entrypoint. The server answers OK whether or not it existed, and never deletes its procedure.

@param name the entrypoint
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception Mpp.Client.Errors.ForbiddenException with code `403-5` when the entrypoint runs as another user and the caller is neither an owner nor an admin

## ListAsync(CancellationToken)
Lists the entrypoints.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the entrypoints

## SaveAsync(EntrypointDefinition, CancellationToken)
Saves an entrypoint, creating it or replacing the stored one. An inline `Procedure` is saved first as an ordinary procedure, and stays saved if the entrypoint cannot be written.

@param definition the entrypoint; exactly one of `ProcedureName` and `Procedure` must be set
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the new version and the version of the procedure it runs
@exception System.ArgumentException when both or neither of `ProcedureName` and `Procedure` are set, or when the inline procedure sets `IfVersion`
@exception Mpp.Client.Errors.ConflictException with code `409-19` when another entrypoint of the project already serves the same method and path, or `409-8` when `IfVersion` does not match the stored version
