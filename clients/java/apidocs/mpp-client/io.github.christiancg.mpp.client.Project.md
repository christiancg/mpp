One project of the server. Obtained from `MppClient.project(name)`; nothing is sent until a method is called.

## name()

@return the project's name

## create()

Creates the project.

## drop()

Drops the project with every collection, procedure and schedule in it.

## listCollections()

@return the names of the project's collections

## collection(String)

@param collection the collection's name
@return the collection, mapping documents as `Document`

## collection(String, Class)

Maps documents to a POJO or record through Jackson. A property named `id` maps to `_id` unless the type
also declares `_id`.

@param collection the collection's name
@param type the class documents map to
@param <T> the mapped type
@return the typed collection

## collection(String, DocumentCodec)

@param collection the collection's name
@param codec maps between documents and `T`
@param <T> the mapped type
@return the typed collection

## stats()

@return this project's part of `GET_PROJECT_STATS`: totals and per-collection memory

## setOwners(List)

Replaces the project's owners. Admin only.

@param owners existing usernames

## runScript(String)

Runs a SimpleJS program scoped to this project.

@param script the program
@return its result, logs and run id

## runScript(String, Document)

Runs a SimpleJS program scoped to this project.

@param script the program
@param args the value the script imports as `args`
@return its result, logs and run id

## procedures()

@return the project's stored procedures

## schedules()

@return the project's scheduled procedures

## entrypoints()

@return the project's HTTP entrypoints

## middlewares()

@return the project's HTTP middlewares

## listTriggers()

@return every trigger of every collection in the project
