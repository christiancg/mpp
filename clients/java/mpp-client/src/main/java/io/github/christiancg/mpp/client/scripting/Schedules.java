package io.github.christiancg.mpp.client.scripting;

import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.Descriptors;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

public final class Schedules {
    private final ClientContext context;
    private final String database;

    public Schedules(ClientContext context, String database) {
        this.context = context;
        this.database = database;
    }

    public long save(ScheduleDefinition schedule) {
        return context.channel().send(Operation.SAVE_SCHEDULE, context.scripts().saveSchedule(database, schedule))
                .path("version").asLong(0);
    }

    public void delete(String name) {
        context.channel().send(Operation.DELETE_SCHEDULE, context.scripts().deleteSchedule(database, name));
    }

    public List<ScheduleInfo> list() {
        return schedules(context.scripts().listSchedules(database));
    }

    public List<ScheduleInfo> list(boolean includeArgs) {
        return schedules(context.scripts().listSchedules(database, includeArgs));
    }

    private List<ScheduleInfo> schedules(ObjectNode request) {
        final var reply = context.channel().send(Operation.LIST_SCHEDULES, request);
        return ResponseDecoder.documents(reply, "schedules").stream().map(Descriptors::schedule).toList();
    }
}
