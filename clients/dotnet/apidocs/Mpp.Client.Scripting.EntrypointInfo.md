An HTTP entrypoint as listed.

@param Name the entrypoint's name
@param Method its HTTP method, upper case
@param Path its route template
@param Url the path to request on the server's `httpPort`
@param ProcedureName the procedure it runs
@param TimeoutMs how long one run may take, in milliseconds
@param Enabled whether it answers
@param Version its version
@param Definer whose authority it runs with
@param Description its description
@param DisabledReason why the server disabled it
@param Raw the row as the server sent it

## EntrypointInfo(string, string, string, string, string, long, bool, long, string, string, string, Document)
Creates the description.

@param Name the entrypoint's name
@param Method its HTTP method, upper case
@param Path its route template
@param Url the path to request on the server's `httpPort`
@param ProcedureName the procedure it runs
@param TimeoutMs how long one run may take, in milliseconds
@param Enabled whether it answers
@param Version its version
@param Definer whose authority it runs with
@param Description its description
@param DisabledReason why the server disabled it
@param Raw the row as the server sent it

## Definer
The user whose authority the entrypoint runs with, or null when not listed.

## Description
Free text describing the entrypoint, or null.

## DisabledReason
Why the server disabled the entrypoint, such as its definer losing script `Run`, or null.

## Enabled
Whether the entrypoint answers.

## Method
The HTTP method, upper case. A string rather than `EntrypointMethod`, so a method a newer server adds still lists.

## Name
The entrypoint's name.

## Path
The route template.

## ProcedureName
The procedure the entrypoint runs.

## Raw
The row as the server sent it.

## TimeoutMs
How long one run may take, in milliseconds; 0 means the server's default.

## Url
The path to request on the server's `httpPort`: the project name followed by the route template.

## Version
The entrypoint's version, which `IfVersion` takes.
