An HTTP entrypoint to save. It takes exactly one of `procedureName`, an existing procedure, and `procedure`, one
saved with it.

## builder(String, EntrypointMethod, String)

@param name the entrypoint's name
@param method the HTTP method it answers
@param path the path template under the project, such as `/orders/{id}`
@return a builder

## name()

@return the name

## method()

@return the HTTP method

## path()

@return the path template

## procedureName()

@return the existing procedure it calls

## procedure()

@return the procedure saved with it

## timeout()

@return a call's timeout

## description()

@return the description

## enabled()

@return whether it is enabled

## ifVersion()

@return the required current version
