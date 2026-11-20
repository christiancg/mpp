package org.ccg.mpp.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.bckg_ops.events.TriggerEvent;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.ScheduleDefinition;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class QueueFairnessTest {
    private final Configuration configuration = Configuration.getInstance();

    @AfterEach
    void restore() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerQueueSize", ConfigKey.TRIGGER_QUEUE_SIZE.defaultValue());
        TestUtils.setPrivateField(configuration, "scheduleQueueSize", ConfigKey.SCHEDULE_QUEUE_SIZE.defaultValue());
    }

    private static TriggerEvent trigger(String db, String name) {
        return new TriggerEvent(EventType.CREATED, db, "coll", name, "p", false, List.of(), "alice", 0);
    }

    private static ScheduleRegistry.Entry schedule(String db, String name) {
        final var definition = new ScheduleDefinition(name, "p", null, 2000L, null, 0L, true, "alice", null, 1L, 1L, 1L,
                "alice");
        return new ScheduleRegistry.Entry(db, definition, null, 0L);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> queued(Object executor) throws Exception {
        final BlockingQueue<T> queue = TestUtils.getPrivateField(executor, "queue", BlockingQueue.class);
        return new ArrayList<>(queue);
    }

    private static TriggerExecutor idleTriggerExecutor() throws Exception {
        final var executor = new TriggerExecutor();
        TestUtils.setPrivateField(executor, "dispatcher", (Consumer<TriggerEvent>) _ -> {
        });
        return executor;
    }

    private static ScheduleExecutor idleScheduleExecutor() throws Exception {
        final var executor = new ScheduleExecutor();
        TestUtils.setPrivateField(executor, "dispatcher", (Consumer<ScheduleRegistry.Entry>) _ -> {
        });
        return executor;
    }

    @Test
    public void test_a_full_trigger_queue_evicts_only_the_flooding_project() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerQueueSize", 3);
        final var executor = idleTriggerExecutor();
        executor.submit(trigger("victimProject", "victim"));
        executor.submit(trigger("floodProject", "f1"));
        executor.submit(trigger("floodProject", "f2"));
        executor.submit(trigger("floodProject", "f3"));
        executor.submit(trigger("floodProject", "f4"));

        final List<TriggerEvent> queue = queued(executor);
        assertEquals(List.of("victim", "f3", "f4"), queue.stream().map(TriggerEvent::getTriggerName).toList());
        assertEquals(2L, executor.getDropped());
    }

    @Test
    public void test_a_full_trigger_queue_drops_the_newcomer_when_its_project_has_nothing_queued() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerQueueSize", 2);
        final var executor = idleTriggerExecutor();
        executor.submit(trigger("a", "a1"));
        executor.submit(trigger("b", "b1"));
        executor.submit(trigger("c", "c1"));

        final List<TriggerEvent> queue = queued(executor);
        assertEquals(List.of("a1", "b1"), queue.stream().map(TriggerEvent::getTriggerName).toList());
        assertEquals(1L, executor.getDropped());
    }

    @Test
    public void test_a_full_schedule_queue_evicts_only_the_flooding_project() throws Exception {
        TestUtils.setPrivateField(configuration, "scheduleQueueSize", 2);
        final var executor = idleScheduleExecutor();
        executor.submit(schedule("victimProject", "victim"));
        executor.submit(schedule("floodProject", "f1"));
        executor.submit(schedule("floodProject", "f2"));
        executor.submit(schedule("otherProject", "o1"));

        final List<ScheduleRegistry.Entry> queue = queued(executor);
        assertEquals(List.of("victim", "f2"), queue.stream().map(ScheduleRegistry.Entry::getName).toList());
        assertEquals(2L, executor.getDropped());
    }
}
