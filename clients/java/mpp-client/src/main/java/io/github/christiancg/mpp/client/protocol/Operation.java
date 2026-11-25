package io.github.christiancg.mpp.client.protocol;

public enum Operation {
    // @formatter:off
    BULK_SAVE(Scope.COLLECTION, false, true, false),
    SAVE(Scope.COLLECTION, false, true, false),
    FIND_BY_ID(Scope.COLLECTION, false, true, true),
    AGGREGATE(Scope.COLLECTION, false, true, true),
    DELETE(Scope.COLLECTION, false, true, false),
    CREATE_PROJECT(Scope.PROJECT, false, false, false),
    DROP_PROJECT(Scope.PROJECT, false, false, false),
    LIST_PROJECTS(Scope.NONE, false, false, true),
    CREATE_COLLECTION(Scope.COLLECTION, false, false, false),
    DROP_COLLECTION(Scope.COLLECTION, false, false, false),
    LIST_COLLECTIONS(Scope.PROJECT, false, false, true),
    CREATE_INDEX(Scope.COLLECTION, false, false, false),
    DROP_INDEX(Scope.COLLECTION, false, false, false),
    REINDEX(Scope.COLLECTION, false, false, false),
    SAVE_SCHEMA(Scope.COLLECTION, false, false, false),
    DELETE_SCHEMA(Scope.COLLECTION, false, false, false),
    GET_SCHEMA(Scope.COLLECTION, false, false, true),
    CLOSE_CONNECTION(Scope.NONE, false, true, false),
    SERVER_INFO(Scope.NONE, false, true, true),
    AUTHENTICATE(Scope.NONE, false, false, false),
    CREATE_USER(Scope.NONE, true, false, false),
    DELETE_USER(Scope.NONE, true, false, false),
    CHANGE_PERMISSIONS(Scope.NONE, true, false, false),
    SET_PROJECT_OWNERS(Scope.PROJECT, true, false, false),
    LIST_USERS(Scope.NONE, true, false, true),
    SET_PASSWORD(Scope.NONE, false, false, false),
    GET_PROJECT_STATS(Scope.PROJECT, false, false, true),
    LISTEN(Scope.COLLECTION, false, false, false),
    STOP_LISTEN(Scope.NONE, false, false, false),
    START_TRANSACTION(Scope.NONE, false, true, false),
    COMMIT_TRANSACTION(Scope.NONE, false, true, false),
    ROLLBACK_TRANSACTION(Scope.NONE, false, true, false),
    RESOLVE_TRANSACTION(Scope.NONE, true, false, false),
    LIST_TRANSACTIONS(Scope.NONE, true, false, true),
    RUN_SCRIPT(Scope.PROJECT, false, false, false),
    SAVE_PROCEDURE(Scope.PROJECT, false, false, false),
    DELETE_PROCEDURE(Scope.PROJECT, false, false, false),
    LIST_PROCEDURES(Scope.PROJECT, false, false, true),
    CALL_PROCEDURE(Scope.PROJECT, false, false, false),
    SAVE_TRIGGER(Scope.COLLECTION, false, false, false),
    DELETE_TRIGGER(Scope.COLLECTION, false, false, false),
    LIST_TRIGGERS(Scope.OPTIONAL_COLLECTION, false, false, true),
    SAVE_SCHEDULE(Scope.PROJECT, false, false, false),
    DELETE_SCHEDULE(Scope.PROJECT, false, false, false),
    LIST_SCHEDULES(Scope.PROJECT, false, false, true),
    SAVE_ENTRYPOINT(Scope.PROJECT, false, false, false),
    DELETE_ENTRYPOINT(Scope.PROJECT, false, false, false),
    LIST_ENTRYPOINTS(Scope.PROJECT, false, false, true),
    SAVE_MIDDLEWARE(Scope.PROJECT, false, false, false),
    DELETE_MIDDLEWARE(Scope.PROJECT, false, false, false),
    LIST_MIDDLEWARES(Scope.PROJECT, false, false, true),
    WRITE_FILE(Scope.PROJECT, false, false, false),
    READ_FILE(Scope.PROJECT, false, true, true),
    STAT_FILE(Scope.PROJECT, false, true, true),
    LIST_FILES(Scope.PROJECT, false, true, true),
    DELETE_FILE(Scope.PROJECT, false, false, false),
    MOVE_FILE(Scope.PROJECT, false, false, false),
    MAKE_DIRECTORY(Scope.PROJECT, false, false, false),
    LIST_SCRIPTS(Scope.NONE, true, false, true),
    CANCEL_SCRIPT(Scope.NONE, true, false, false),
    TEST_TRIGGER(Scope.COLLECTION, false, false, false),
    LIST_TRIGGER_RUNS(Scope.NONE, true, false, true),
    RESOLVE_TRIGGER_RUN(Scope.NONE, true, false, false);
    // @formatter:on

    public enum Scope {
        PROJECT, COLLECTION, OPTIONAL_COLLECTION, NONE
    }

    private final Scope scope;
    private final boolean adminOnly;
    private final boolean allowedInTransaction;
    private final boolean read;

    Operation(Scope scope, boolean adminOnly, boolean allowedInTransaction, boolean read) {
        this.scope = scope;
        this.adminOnly = adminOnly;
        this.allowedInTransaction = allowedInTransaction;
        this.read = read;
    }

    public Scope scope() {
        return scope;
    }

    public boolean adminOnly() {
        return adminOnly;
    }

    public boolean allowedInTransaction() {
        return allowedInTransaction;
    }

    public boolean isRead() {
        return read;
    }
}
