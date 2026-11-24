An HTTP middleware as listed.

@param Name the middleware's name
@param Phase its phase, upper case
@param Order its order within the phase
@param Match the entrypoints it wraps
@param ProcedureName the procedure it runs
@param TimeoutMs how long one run may take, in milliseconds
@param Enabled whether it runs
@param Version its version
@param Definer whose authority it runs with
@param Description its description
@param DisabledReason why the server disabled it
@param Raw the row as the server sent it

## MiddlewareInfo(string, string, int, Document, string, long, bool, long, string, string, string, Document)
Creates the description.

@param Name the middleware's name
@param Phase its phase, upper case
@param Order its order within the phase
@param Match the entrypoints it wraps
@param ProcedureName the procedure it runs
@param TimeoutMs how long one run may take, in milliseconds
@param Enabled whether it runs
@param Version its version
@param Definer whose authority it runs with
@param Description its description
@param DisabledReason why the server disabled it
@param Raw the row as the server sent it

## Definer
The user whose authority the middleware runs with, or null when not listed.

## Description
Free text describing the middleware, or null.

## DisabledReason
Why the server disabled the middleware, such as its definer losing script `Run`, or null. Every entrypoint a disabled middleware wraps answers `500`.

## Enabled
Whether the middleware runs. One saved disabled is skipped.

## Match
The entrypoints the middleware wraps, as the server sent them (`methods`, `pathPrefix`, `entrypoints` and `exclude`), or null when it wraps every entrypoint.

## Name
The middleware's name.

## Order
Where the middleware runs within its phase, by ascending order.

## Phase
The phase, upper case. A string rather than `MiddlewarePhase`, so a phase a newer server adds still lists.

## ProcedureName
The procedure the middleware runs.

## Raw
The row as the server sent it.

## TimeoutMs
How long one run may take, in milliseconds; 0 means no cap of its own.

## Version
The middleware's version, which `IfVersion` takes.
