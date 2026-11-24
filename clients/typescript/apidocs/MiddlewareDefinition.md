# MiddlewareDefinition

A middleware to save. It takes exactly one of `procedureName` and `procedure`.

## name

Its name.

## phase

Whether it runs before or after the entrypoint's procedure.

## order

Optional: its place within its phase, ascending, ties in name order; `0` when missing.

## match

Optional: the entrypoints it wraps; every entrypoint of the project when missing.

## procedureName

The existing procedure it runs.

## procedure

A procedure saved together with the middleware, as `Procedures.save` would save it. It takes no `ifVersion`.

## timeoutMs

Optional time limit of each run; `0` or missing means no limit of its own beyond the entrypoint's.

## description

Optional description.

## enabled

Optional; enabled by default. A disabled middleware is skipped.

## ifVersion

Optional: save only when the stored version is this one.
