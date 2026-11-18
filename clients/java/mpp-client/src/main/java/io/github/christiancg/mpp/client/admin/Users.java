package io.github.christiancg.mpp.client.admin;

import io.github.christiancg.mpp.client.error.NotFoundException;
import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.Descriptors;
import io.github.christiancg.mpp.client.internal.PipelineStep;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.protocol.ErrorCode;
import io.github.christiancg.mpp.client.protocol.Operation;
import io.github.christiancg.mpp.client.query.Filter;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

public final class Users {
    private final ClientContext context;

    Users(ClientContext context) {
        this.context = context;
    }

    public void create(String username, String password) {
        create(context.scripts().createUser(username, password));
    }

    public void create(String username, String password, Permissions permissions) {
        create(context.scripts().createUser(username, password, permissions));
    }

    public void delete(String username) {
        context.channel().send(Operation.DELETE_USER, context.scripts().deleteUser(username));
    }

    public void changePermissions(String username, Permissions permissions) {
        context.channel().send(Operation.CHANGE_PERMISSIONS,
                context.scripts().changePermissions(username, permissions));
    }

    public void setPassword(String username, String newPassword) {
        context.channel().send(Operation.SET_PASSWORD, context.scripts().setPassword(username, newPassword));
    }

    public List<UserInfo> list() {
        return list(List.of());
    }

    public List<UserInfo> list(Filter filter) {
        return list(List.of(new PipelineStep.FilterStep(filter)));
    }

    private void create(ObjectNode request) {
        context.channel().send(Operation.CREATE_USER, request);
    }

    private List<UserInfo> list(List<PipelineStep> steps) {
        try {
            final var reply = context.channel().send(Operation.LIST_USERS, context.scripts().listUsers(steps));
            return ResponseDecoder.documents(reply, "users").stream().map(Descriptors::user).toList();
        } catch (NotFoundException e) {
            if (e.hasCode(ErrorCode.NO_USERS_FOUND) || e.hasCode(ErrorCode.NO_RESULTS)) {
                return List.of();
            }
            throw e;
        }
    }
}
