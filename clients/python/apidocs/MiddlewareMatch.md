# MiddlewareMatch

The entrypoints a middleware wraps: every criterion given must hold. `path_prefix` covers whole literal segments, and
`exclude` leaves out the entrypoints any of its rules holds for (at most 16 rules).
