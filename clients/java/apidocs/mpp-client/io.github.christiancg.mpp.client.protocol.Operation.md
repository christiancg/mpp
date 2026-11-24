The wire operations a client may send, from the server's catalogue. The fault-injection operations
are test-only and left out.

## BULK_SAVE

Scope collection; allowed inside a transaction.

## SAVE

Scope collection; allowed inside a transaction.

## FIND_BY_ID

Scope collection; allowed inside a transaction.

## AGGREGATE

Scope collection; allowed inside a transaction.

## DELETE

Scope collection; allowed inside a transaction.

## CREATE_PROJECT

Scope project.

## DROP_PROJECT

Scope project.

## LIST_PROJECTS

Scope none.

## CREATE_COLLECTION

Scope collection.

## DROP_COLLECTION

Scope collection.

## LIST_COLLECTIONS

Scope project.

## CREATE_INDEX

Scope collection.

## DROP_INDEX

Scope collection.

## REINDEX

Scope collection.

## SAVE_SCHEMA

Scope collection.

## DELETE_SCHEMA

Scope collection.

## GET_SCHEMA

Scope collection.

## CLOSE_CONNECTION

Scope none; allowed inside a transaction.

## SERVER_INFO

Scope none; allowed inside a transaction.

## AUTHENTICATE

Scope none.

## CREATE_USER

Scope none; admin only.

## DELETE_USER

Scope none; admin only.

## CHANGE_PERMISSIONS

Scope none; admin only.

## SET_PROJECT_OWNERS

Scope project; admin only.

## LIST_USERS

Scope none; admin only.

## SET_PASSWORD

Scope none.

## GET_PROJECT_STATS

Scope project.

## LISTEN

Scope collection.

## STOP_LISTEN

Scope none.

## START_TRANSACTION

Scope none; allowed inside a transaction.

## COMMIT_TRANSACTION

Scope none; allowed inside a transaction.

## ROLLBACK_TRANSACTION

Scope none; allowed inside a transaction.

## RESOLVE_TRANSACTION

Scope none; admin only.

## LIST_TRANSACTIONS

Scope none; admin only.

## RUN_SCRIPT

Scope project.

## SAVE_PROCEDURE

Scope project.

## DELETE_PROCEDURE

Scope project.

## LIST_PROCEDURES

Scope project.

## CALL_PROCEDURE

Scope project.

## SAVE_TRIGGER

Scope collection.

## DELETE_TRIGGER

Scope collection.

## LIST_TRIGGERS

Scope optional collection.

## SAVE_SCHEDULE

Scope project.

## DELETE_SCHEDULE

Scope project.

## LIST_SCHEDULES

Scope project.

## SAVE_ENTRYPOINT

Scope project.

## DELETE_ENTRYPOINT

Scope project.

## LIST_ENTRYPOINTS

Scope project.

## SAVE_MIDDLEWARE

Scope project.

## DELETE_MIDDLEWARE

Scope project.

## LIST_MIDDLEWARES

Scope project.

## LIST_SCRIPTS

Scope none; admin only.

## CANCEL_SCRIPT

Scope none; admin only.

## TEST_TRIGGER

Scope collection.

## LIST_TRIGGER_RUNS

Scope none; admin only.

## RESOLVE_TRIGGER_RUN

Scope none; admin only.

## scope()

@return what the operation addresses

## adminOnly()

@return `true` when only admins may send it

## allowedInTransaction()

@return `true` when it may be sent while a transaction is open

## isRead()

@return `true` when it changes nothing, so a lost connection may be retried
