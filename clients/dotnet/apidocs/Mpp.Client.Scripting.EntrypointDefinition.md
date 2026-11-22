An HTTP entrypoint to save: a method and path that run a stored procedure.

Set exactly one of `ProcedureName` and `Procedure`.

@param Name the entrypoint's name
@param Method the HTTP method it answers
@param Path the route template it answers, such as `/orders/{id}`

## EntrypointDefinition(string, EntrypointMethod, string)
Creates a definition.

@param Name the entrypoint's name
@param Method the HTTP method it answers
@param Path the route template it answers, such as `/orders/{id}`

## Description
Free text describing the entrypoint, or null.

## Enabled
False keeps the entrypoint stored but answering `404`; null leaves it to the server's default, which is enabled.

## IfVersion
The version the stored definition must have, or the save is refused with `409-8`. Null saves unconditionally.

## Method
The HTTP method the entrypoint answers.

## Name
The entrypoint's name.

## Path
The route template the entrypoint answers, such as `/orders/{id}`. The server stores it without a trailing `/`.

## Procedure
A procedure saved with the entrypoint and run by it, or null when `ProcedureName` is used. It must not set `IfVersion`.

## ProcedureName
The existing procedure the entrypoint runs, or null when `Procedure` is used.

## Timeout
How long one run may take, or null for the server's default. The server refuses one above `entrypointMaxTimeoutMs` (`400-48`).
