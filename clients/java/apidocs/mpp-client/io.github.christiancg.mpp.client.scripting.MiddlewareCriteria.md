Criteria that select entrypoints. Every criterion given must hold; criteria with none select every entrypoint.

@param methods the HTTP methods, empty for any
@param pathPrefix the literal path segments the entrypoint's path starts with, such as `/orders`
@param entrypoints the entrypoint names, empty for any; they don't need to exist yet

## builder()

@return a builder with no criterion
