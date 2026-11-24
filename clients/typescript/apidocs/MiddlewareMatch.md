# MiddlewareMatch

The entrypoints a middleware wraps: those its own criteria hold for, minus those any `exclude` rule holds for. A
missing or empty match wraps every entrypoint of the project.

## exclude

Optional: at most 16 rules, each with at least one criterion, leaving out the entrypoints they hold for.
