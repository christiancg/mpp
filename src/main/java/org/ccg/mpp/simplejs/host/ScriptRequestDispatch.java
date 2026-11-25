package org.ccg.mpp.simplejs.host;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.cluster.ClusterRouter;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.SchemaValidationHelper;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.CommitTransactionRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.RollbackTransactionRequest;
import org.ccg.mpp.ops.req.StartTransactionRequest;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.ResponseParser;
import org.ccg.mpp.simplejs.builtins.ErrorBuiltins;
import org.ccg.mpp.simplejs.builtins.Intrinsics;
import org.ccg.mpp.simplejs.exceptions.JsThrowException;

public final class ScriptRequestDispatch {
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final ClusterRouter clusterRouter = IocContainer.get(ClusterRouter.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    private final AtomicLong operations = new AtomicLong();

    private final String username;
    private final UUID clientId;
    private final String scopedProject;
    private final int triggerDepth;
    private final ScriptSession session;
    private Intrinsics intrinsics;
    private volatile boolean clusterUnavailable;

    ScriptRequestDispatch(String username, UUID clientId, String scopedProject, int triggerDepth,
            ScriptSession session) {
        this.username = username;
        this.clientId = clientId;
        this.scopedProject = scopedProject;
        this.triggerDepth = triggerDepth;
        this.session = session;
    }

    String username() {
        return username;
    }

    UUID clientId() {
        return clientId;
    }

    String scopedProject() {
        return scopedProject;
    }

    long operationCount() {
        return operations.get();
    }

    boolean sawClusterUnavailable() {
        return clusterUnavailable;
    }

    void useIntrinsics(Intrinsics value) {
        this.intrinsics = value;
    }

    OperationResponse dispatch(OperationRequest request) {
        return dispatch(request, null);
    }

    OperationResponse dispatch(OperationRequest request, String rawJson) {
        session.assertSessionThread();
        operations.incrementAndGet();
        request.setTriggerDepth(triggerDepth);
        if (scopedProject != null) {
            final var targetProject = request.getProjectName();
            if (targetProject != null && !scopedProject.equals(targetProject)) {
                throw jsError("This script may only access project '" + scopedProject + "'");
            }
        }
        final var validation = RequestValidator.validate(request);
        if (!validation.isValid()) {
            throw jsError(validation.getErrorMessage());
        }
        if (!isTransactionControl(request)) {
            final var user = cache.getAdminUserEntry(username);
            if (user == null) {
                throw jsError("User '" + username + "' not found");
            }
            final var authorization = AuthorizationChecker.check(request, user);
            if (!authorization.isAllowed()) {
                throw jsError(authorization.getReason());
            }
        }
        final var schemaError = SchemaValidationHelper.check(request);
        if (schemaError != null) {
            throw jsError(schemaError.getMessage());
        }
        final var sessionClientId = session.sessionClientId();
        if (sessionClientId != null) {
            return recordClusterAvailability(routeOrProcess(request, rawJson, sessionClientId));
        }
        if (clientId != null) {
            return recordClusterAvailability(routeOrProcess(request, rawJson, clientId));
        }
        final var forwardedClientId = clientTracker.registerForwardedClient(username);
        try {
            return recordClusterAvailability(routeOrProcess(request, rawJson, forwardedClientId));
        } finally {
            clientTracker.removeById(forwardedClientId);
        }
    }

    private OperationResponse recordClusterAvailability(OperationResponse response) {
        if (ErrorCode.NO_QUORUM.getCode().equals(response.getErrorCode())
                || ErrorCode.NOT_COLLECTION_OWNER.getCode().equals(response.getErrorCode())) {
            clusterUnavailable = true;
        }
        return response;
    }

    private OperationResponse routeOrProcess(OperationRequest request, String rawJson, UUID effectiveClientId) {
        if (clusterConfig.isEnabled()) {
            final var forwarded = clusterRouter.forward(request, rawJson != null ? rawJson : eJson.toJson(request),
                    session.sessionClientId() != null, username, effectiveClientId);
            if (forwarded != null) {
                try {
                    return ResponseParser.parseResponse(forwarded);
                } catch (RuntimeException e) {
                    throw jsError("Unreadable response from the collection's owner: " + e.getMessage());
                }
            }
        }
        return operationProcessor.processMessage(request, effectiveClientId);
    }

    private static boolean isTransactionControl(OperationRequest request) {
        return request instanceof StartTransactionRequest || request instanceof CommitTransactionRequest
                || request instanceof RollbackTransactionRequest;
    }

    JsThrowException jsError(String message) {
        return new JsThrowException(intrinsics == null
                ? ErrorBuiltins.makeError("Error", message)
                : intrinsics.makeError("Error", message));
    }
}
