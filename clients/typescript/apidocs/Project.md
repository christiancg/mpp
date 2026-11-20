# Project

One project of the server. Obtained from `MppClient.project(name)`; the name is validated
locally, and nothing is sent until a method is called.

## name

The project's name.

## create

Creates the project. A project that already exists is refused with `409-2`.

## drop

Drops the project with every collection, procedure and schedule in it.

## listCollections

@returns the names of the project's collections

## collection

Names a collection of this project.

Without a codec, documents are plain objects: `T` describes them for the compiler only, and reads return
`Stored<T>`, which adds `_id`. With a codec, `encode` runs before every write and `decode` after every read.

@param name 3-64 characters from `A-Z a-z 0-9 _ -`
@param codec optional mapping between your type and stored documents
@returns the collection

## stats

@returns the project's statistics document

## setOwners

Replaces the project's owners. Admin only.

@param owners the usernames that own the project

## runScript

Runs a SimpleJS script against the project.

@param script the script source
@param args optional arguments, visible to the script
@returns the script's value, its logs and its run id

## procedures

@returns the project's stored procedures

## schedules

@returns the project's scheduled procedures

## listTriggers

@returns every trigger on every collection of the project
