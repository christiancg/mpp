package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.StoredDefinition;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.VersionedDefinitionRequest;
import org.ccg.mpp.ops.resp.OperationResponse;

abstract class BoundDefinitionSave<T extends StoredDefinition> {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final Configuration configuration = Configuration.getInstance();

    record Kind(OperationType type, ErrorCode savingError, String folder, ConfigKey cap, ErrorCode tooMany) {
    }

    private final Kind kind;
    private final VersionedDefinitionRequest request;
    private final String name;
    private final String procedureName;
    private final SaveProcedureRequest inline;

    BoundDefinitionSave(Kind kind, VersionedDefinitionRequest request, String name, String procedureName,
            SaveProcedureRequest inline) {
        this.kind = kind;
        this.request = request;
        this.name = name;
        this.procedureName = procedureName;
        this.inline = inline;
    }

    protected abstract T existing(String projectName, String name);

    protected abstract String definerOf(T definition);

    protected abstract List<String> names(String projectName) throws IOException;

    protected OperationResponse refusalAmong(List<String> names) {
        return null;
    }

    protected abstract OperationResponse persist(T existing, long procedureVersion) throws IOException;

    static OperationResponse inlineRefusal(OperationType type, SaveProcedureRequest inline) {
        return inline == null ? null : ProcedureOperationHelper.refusalBeforeSave(type, inline);
    }

    final OperationResponse run(String actingUser) {
        final var projectName = request.getProjectName();
        return OperationLocks.withProjectShared(projectName, kind.type(), kind.savingError(),
                () -> saveUnderProjectBarrier(projectName, actingUser));
    }

    private OperationResponse saveUnderProjectBarrier(String projectName, String actingUser)
            throws IOException, InterruptedException {
        if (cache.getAdminProjectEntry(projectName) == null) {
            return new OperationResponse(kind.type(), "Project '" + projectName + "' not found",
                    ErrorCode.PROJECT_NOT_FOUND);
        }
        locks.lock(projectName, kind.folder());
        locks.lock(projectName, Globals.PROCEDURES_FOLDER);
        try {
            final var existing = existing(projectName, name);
            final var refusal = refusalUnderLock(projectName, existing, actingUser);
            if (refusal != null) {
                return refusal;
            }
            final long procedureVersion;
            if (inline != null) {
                final var outcome = ProcedureOperationHelper.saveHoldingLock(kind.type(), inline, actingUser);
                if (outcome.refusal() != null) {
                    return outcome.refusal();
                }
                procedureVersion = outcome.version();
            } else {
                final var procedure = cache.getProcedure(projectName, procedureName);
                if (procedure == null) {
                    return new OperationResponse(kind.type(),
                            "Procedure '" + procedureName + "' not found in project '" + projectName + "'",
                            ErrorCode.PROCEDURE_NOT_FOUND);
                }
                procedureVersion = procedure.getVersion();
            }
            return persist(existing, procedureVersion);
        } finally {
            locks.release(projectName, Globals.PROCEDURES_FOLDER);
            locks.release(projectName, kind.folder());
        }
    }

    private OperationResponse refusalUnderLock(String projectName, T existing, String actingUser) throws IOException {
        if (request.conflictsWith(existing == null ? 0L : existing.getVersion())) {
            return new OperationResponse(kind.type(), ErrorCode.PROCEDURE_VERSION_CONFLICT);
        }
        final var foreign = existing == null
                ? null
                : ProcedureBindingGuard.foreignDefinitionRefusal(kind.type(), projectName, definerOf(existing),
                        actingUser);
        if (foreign != null) {
            return foreign;
        }
        final var names = names(projectName);
        final var max = configuration.intValue(kind.cap());
        if (existing == null && names.size() >= max) {
            return new OperationResponse(kind.type(), kind.tooMany().getDefaultMessage() + " (" + max + ")",
                    kind.tooMany());
        }
        final var colliding = OnDiskNameRegistry.collidingDefinition(names, name);
        if (colliding != null) {
            return new OperationResponse(kind.type(), ErrorCode.NAME_COLLIDES_ON_DISK, colliding);
        }
        return refusalAmong(names);
    }
}
