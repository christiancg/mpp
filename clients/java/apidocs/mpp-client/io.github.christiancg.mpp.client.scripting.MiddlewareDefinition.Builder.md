Builds a `MiddlewareDefinition`.

## order(int)

@param order its place within its phase, lower first; ties run in name order
@return this builder

## match(MiddlewareMatch)

@param match the entrypoints it wraps; without one it wraps every entrypoint of the project
@return this builder

## procedureName(String)

@param procedureName an existing procedure to run
@return this builder

## procedure(ProcedureDefinition)

@param procedure a procedure to save with the middleware, without `ifVersion`
@return this builder

## timeout(Duration)

@param timeout how long one run may take
@return this builder

## description(String)

@param description free text
@return this builder

## enabled(boolean)

@param enabled `false` keeps it stored but skipped
@return this builder

## ifVersion(long)

@param version the version the stored definition must have, or the save is refused
@return this builder

## build()

@return the definition
@throws IllegalArgumentException unless exactly one of procedureName and procedure is set, or when the inline
procedure sets ifVersion
