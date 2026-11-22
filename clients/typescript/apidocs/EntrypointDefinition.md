# EntrypointDefinition

An entrypoint to save. It takes exactly one of `procedureName` and `procedure`.

## name

Its name.

## method

The HTTP method it answers.

## path

The route template it answers, such as `/orders/{id}`.

## procedureName

The existing procedure it runs.

## procedure

A procedure saved together with the entrypoint, as `Procedures.save` would save it. It takes no `ifVersion`.

## timeoutMs

Optional time limit of each run; the server's default when missing.

## description

Optional description.

## enabled

Optional; enabled by default.

## ifVersion

Optional: save only when the stored version is this one.
