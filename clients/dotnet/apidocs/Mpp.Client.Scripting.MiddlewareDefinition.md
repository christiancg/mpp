An HTTP middleware to save: a stored procedure that wraps the entrypoints its `Match` selects.

Set exactly one of `ProcedureName` and `Procedure`.

@param Name the middleware's name
@param Phase whether it runs before or after the entrypoint's procedure

## MiddlewareDefinition(string, MiddlewarePhase)
Creates a definition.

@param Name the middleware's name
@param Phase whether it runs before or after the entrypoint's procedure

## Description
Free text describing the middleware, or null.

## Enabled
False keeps the middleware stored but skipped; null leaves it to the server's default, which is enabled.

## IfVersion
The version the stored definition must have, or the save is refused with `409-8`. Null saves unconditionally.

## Match
The entrypoints the middleware wraps, or null to wrap every entrypoint of the project, including the ones saved later.

## Name
The middleware's name.

## Order
Where the middleware runs within its phase, by ascending order with ties in name order, or null for the server's default, `0`.

## Phase
Whether the middleware runs before (`Ingress`) or after (`Egress`) the entrypoint's procedure.

## Procedure
A procedure saved with the middleware and run by it, or null when `ProcedureName` is used. It must not set `IfVersion`.

## ProcedureName
The existing procedure the middleware runs, or null when `Procedure` is used.

## Timeout
How long one run of this middleware may take, or null for no cap of its own. The entrypoint's timeout still bounds the whole chain, and the server refuses one above `entrypointMaxTimeoutMs` (`400-49`).
