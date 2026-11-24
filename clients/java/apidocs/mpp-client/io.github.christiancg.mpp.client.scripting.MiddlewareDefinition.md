An HTTP middleware to save: a stored procedure that runs before or after the project's entrypoints. It takes exactly
one of `procedureName`, an existing procedure, and `procedure`, one saved with it.

## builder(String, MiddlewarePhase)

@param name the middleware's name
@param phase whether it runs before or after the entrypoint
@return a builder

## name()

@return the name

## phase()

@return the phase

## order()

@return its place within its phase

## match()

@return the entrypoints it wraps

## procedureName()

@return the existing procedure it runs

## procedure()

@return the procedure saved with it

## timeout()

@return one run's timeout

## description()

@return the description

## enabled()

@return whether it is enabled

## ifVersion()

@return the required current version
