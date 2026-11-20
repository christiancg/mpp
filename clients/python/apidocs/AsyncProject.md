# AsyncProject

One project of the server, obtained from `client.project(name)`.

The sync class `Project` has the same members; its methods block and return the value directly.

## name

The project's name.

## create

Creates the project.

Raises:
    ConflictError: it already exists (`409-2`)

## drop

Drops the project with every collection, procedure and schedule in it.

## list_collections

Lists the project's collections.

## collection

Returns one collection of this project.

With only a name, documents are plain dicts. With a dataclass `model`, documents map through
`DataclassCodec(model)`; with `codec`, through that `DocumentCodec`.

Args:
    name: 3-64 characters from A-Z a-z 0-9 _ -
    model: a dataclass type to map documents to
    codec: a codec that maps your type to and from a document

## stats

Returns this project's statistics document.

## set_owners

Replaces the project's owners. Admin only.

Args:
    owners: the usernames that own the project

## run_script

Runs an ad-hoc SimpleJS script in this project.

Args:
    script: the script source
    args: a document the script reads as its arguments

Returns:
    the script's result, its logs and its run id

## procedures

Returns the stored procedures of this project.

## schedules

Returns the scheduled procedures of this project.

## list_triggers

Lists the triggers of every collection in this project.
