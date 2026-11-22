One project. Obtained from `MppClient.Project`.

A handle sends nothing when created; every method borrows a pooled connection for the length of one request.

## Collection(string)
Returns a collection whose documents are `Document` values.

@param name the collection name: 3 to 64 characters from `A-Z a-z 0-9 _ -`
@returns the collection handle
@exception System.ArgumentException when the name is not valid

## Collection<T>(string, IDocumentCodec<T>)
Returns a collection whose documents are mapped to `T`.

@typeparam T the type documents are mapped to
@param name the collection name: 3 to 64 characters from `A-Z a-z 0-9 _ -`
@param codec how `T` maps to and from a document; when null, a `JsonCodec<T>` over `ClientSettings.Json`
@returns the collection handle
@exception System.ArgumentException when the name is not valid, or `T` does not map to a JSON object

## CreateAsync(CancellationToken)
Creates the project.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception Mpp.Client.Errors.ConflictException with code `409-2` when it already exists

## DropAsync(CancellationToken)
Drops the project with every collection, index, schema, procedure, trigger and schedule in it.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception Mpp.Client.Errors.NotFoundException with code `404-4` when it does not exist

## Entrypoints()
Returns the project's HTTP entrypoints.

@returns the entrypoints facade

## ListCollectionsAsync(CancellationToken)
Lists the project's collections.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the collection names

## ListTriggersAsync(CancellationToken)
Lists the triggers of every collection in the project.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the triggers, each with its `Collection`

## Procedures()
Returns the project's stored procedures.

@returns the procedures facade

## RunScriptAsync(string, Document, CancellationToken)
Runs a script on the server's embedded JavaScript engine (`RUN_SCRIPT`). It needs the script permission and `scriptsEnabled` on the server.

@param script the script source
@param args the value the script imports as `args`, or null for none
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the script's value and log lines
@exception Mpp.Client.Errors.ForbiddenException with code `403-2` when scripts are disabled on the server

## Schedules()
Returns the project's scheduled procedures.

@returns the schedules facade

## SetOwnersAsync(IEnumerable<string>, CancellationToken)
Replaces the project's owners. Admin only.

@param owners the user names of the new owners
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown

## StatsAsync(CancellationToken)
Reads the project's statistics (`GET_PROJECT_STATS`).

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the statistics as the server reports them

## Name
The project's name.
