package org.ccg.mpp.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.resp.GetProjectStatsResponse;
import org.ccg.mpp.ops.resp.GetSchemaResponse;
import org.ccg.mpp.ops.resp.ListCollectionsResponse;
import org.ccg.mpp.ops.resp.ListProceduresResponse;
import org.ccg.mpp.ops.resp.ListProjectsResponse;
import org.ccg.mpp.ops.resp.ListSchedulesResponse;
import org.ccg.mpp.ops.resp.ListScriptsResponse;
import org.ccg.mpp.ops.resp.ListTransactionsResponse;
import org.ccg.mpp.ops.resp.ListTriggerRunsResponse;
import org.ccg.mpp.ops.resp.ListTriggersResponse;
import org.ccg.mpp.ops.resp.ListUsersResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.junit.jupiter.api.Test;

public class ListingResponseEstimateTest {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final int PAYLOAD_CHARS = 100_000;
    private static final String LARGE = "s".repeat(PAYLOAD_CHARS);

    private static JsonObject large() {
        final var object = new JsonObject();
        object.addProperty("source", LARGE);
        return object;
    }

    private static void assertCoversItsJson(OperationResponse response) {
        final var encoded = eJson.toJson(response).length();
        final var estimate = response.estimatedJsonChars();
        assertTrue(encoded > PAYLOAD_CHARS, response.getClass().getSimpleName());
        assertTrue(estimate >= PAYLOAD_CHARS && estimate >= encoded / 2,
                () -> response.getClass().getSimpleName() + " estimates " + estimate + " for " + encoded);
    }

    @Test
    public void everyRefusableListingEstimatesItsPayload() {
        final List<Function<List<JsonObject>, OperationResponse>> objectLists = List.of(
                rows -> new ListProceduresResponse("ok", rows), rows -> new ListSchedulesResponse("ok", rows),
                rows -> new ListScriptsResponse("ok", rows), rows -> new ListTransactionsResponse("ok", rows),
                rows -> new ListTriggerRunsResponse("ok", rows), rows -> new ListTriggersResponse("ok", rows),
                rows -> new ListUsersResponse("ok", rows));
        for (final var listing : objectLists) {
            assertCoversItsJson(listing.apply(List.of(large())));
        }
        assertCoversItsJson(new GetProjectStatsResponse("ok", large()));
        assertCoversItsJson(new GetSchemaResponse("ok", large()));
        assertCoversItsJson(new ListCollectionsResponse("ok", List.of(LARGE, "b")));
        assertCoversItsJson(new ListProjectsResponse("ok", List.of(LARGE)));
    }

    @Test
    public void anAbsentListingEstimatesOnlyItsEnvelope() {
        assertTrue(new ListProjectsResponse("ok", null).estimatedJsonChars() < 300);
        assertTrue(new ListProceduresResponse("ok", null).estimatedJsonChars() < 300);
    }
}
