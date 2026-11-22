Builds an `EntrypointDefinition`.

## procedureName(String)

@param procedureName an existing procedure to call
@return this builder

## procedure(ProcedureDefinition)

@param procedure a procedure to save with the entrypoint, without `ifVersion`
@return this builder

## timeout(Duration)

@param timeout how long one call may take
@return this builder

## description(String)

@param description free text
@return this builder

## enabled(boolean)

@param enabled `false` keeps it stored but answering 404
@return this builder

## ifVersion(long)

@param version the version the stored definition must have, or the save is refused
@return this builder

## build()

@return the definition
@throws IllegalArgumentException unless exactly one of procedureName and procedure is set, or when the inline
procedure sets ifVersion
