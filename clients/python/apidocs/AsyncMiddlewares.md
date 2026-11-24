# AsyncMiddlewares

The HTTP middlewares of a project: stored procedures that run before (`INGRESS`) or after (`EGRESS`) the procedure of
every entrypoint their `match` selects.

The sync class `Middlewares` has the same members; its methods block and return the value directly.

## save

Saves a middleware; it takes exactly one of `procedure_name` and an inline `procedure`. Saving an existing name
replaces it.

An inline procedure is saved with the middleware and takes no `if_version`.

Returns:
    the middleware's new version and the version of the procedure it runs

Raises:
    ValueError: neither or both of `procedure_name` and `procedure`, or an inline procedure with an `if_version`
    MppError: an invalid phase, match or timeout (`400-49`), or more than `maxMiddlewaresPerProject` (`400-50`)

## delete

Deletes a middleware by name; an inline procedure it saved stays.

## list

Lists the project's middlewares in execution order: `INGRESS` before `EGRESS`, then by `order`, then by name.
