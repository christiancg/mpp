The entrypoints a middleware wraps: those `include` selects, less those any `exclude` rule selects.

@param include the criteria an entrypoint must meet; empty criteria select every entrypoint of the project
@param exclude rules that leave an entrypoint out when any of them holds, at most 16, none of them empty

## of(MiddlewareCriteria, MiddlewareCriteria...)

@param include the criteria an entrypoint must meet
@param exclude rules that leave an entrypoint out
@return the match
