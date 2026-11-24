The HTTP middlewares of one project. Obtained from `Project.Middlewares()`.

A middleware wraps the project's entrypoints with a stored procedure of its own: `Ingress` middlewares run before the entrypoint's procedure and `Egress` ones after it, each phase by ascending `Order`. A middleware calls `next` to continue or `reject` to answer right away. It runs with its definer's permissions. Saving one needs the script permission `Manage`.

## DeleteAsync(string, CancellationToken)
Deletes a middleware. The server answers OK whether or not it existed, and never deletes its procedure.

@param name the middleware
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception Mpp.Client.Errors.ForbiddenException with code `403-5` when the middleware runs as another user and the caller is neither an owner nor an admin

## ListAsync(CancellationToken)
Lists the middlewares in execution order: `INGRESS` before `EGRESS`, then by order, then by name.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the middlewares

## SaveAsync(MiddlewareDefinition, CancellationToken)
Saves a middleware, creating it or replacing the stored one. An inline `Procedure` is saved first as an ordinary procedure, and stays saved if the middleware cannot be written.

@param definition the middleware; exactly one of `ProcedureName` and `Procedure` must be set
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the new version and the version of the procedure it runs
@exception System.ArgumentException when both or neither of `ProcedureName` and `Procedure` are set, or when the inline procedure sets `IfVersion`
@exception Mpp.Client.Errors.MppException with code `400-49` when the phase, a method, the path prefix, an entrypoint name, the timeout or an exclusion rule is not valid, or `400-50` when the project already has `maxMiddlewaresPerProject` middlewares
@exception Mpp.Client.Errors.ConflictException with code `409-8` when `IfVersion` does not match the stored version
