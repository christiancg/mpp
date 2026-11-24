# MiddlewareDefinition

A middleware to save: its phase, an optional `order` (`0` when missing) and `match`, and exactly one of
`procedure_name` and an inline `procedure`. Without a `match` it wraps every entrypoint of the project.
