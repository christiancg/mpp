# AsyncEntrypoints

The HTTP entrypoints of a project: routes that run a stored procedure for an HTTP request.

The sync class `Entrypoints` has the same members; its methods block and return the value directly.

## save

Saves an entrypoint; it takes exactly one of `procedure_name` and an inline `procedure`.

An inline procedure is saved with the entrypoint and takes no `if_version`.

Returns:
    the entrypoint's new version and the version of the procedure it runs

Raises:
    ValueError: neither or both of `procedure_name` and `procedure`, or an inline procedure with an `if_version`
    ConflictError: another entrypoint of the project serves the same method and path (`409-19`)

## delete

Deletes an entrypoint by name; an inline procedure it saved stays.

## list

Lists the project's entrypoints.
