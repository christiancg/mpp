package org.ccg.mpp.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropIndexRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.ListCollectionsRequest;
import org.ccg.mpp.ops.req.ListProjectsRequest;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.agg.step.CountAggregationStep;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.junit.jupiter.api.Test;

public class ProjectRequestValidatorTest {
    @Test
    public void validate_listProjects_returnsOk() {
        assertTrue(RequestValidator.validate(new ListProjectsRequest()).isValid());
    }

    @Test
    public void validate_createProject_validName_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateProjectRequest("myProject")).isValid());
    }

    @Test
    public void validate_createProject_nullName_returnsFail() {
        final var result = RequestValidator.validate(new CreateProjectRequest(null));
        assertFalse(result.isValid());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    public void validate_createProject_tooShortName_returnsFail() {
        final var result = RequestValidator.validate(new CreateProjectRequest("ab"));
        assertFalse(result.isValid());
    }

    @Test
    public void validate_createProject_tooLongName_returnsFail() {
        final var result = RequestValidator.validate(new CreateProjectRequest("a".repeat(65)));
        assertFalse(result.isValid());
    }

    @Test
    public void validate_createProject_invalidChars_returnsFail() {
        final var result = RequestValidator.validate(new CreateProjectRequest("my db!"));
        assertFalse(result.isValid());
    }

    @Test
    public void validate_createProject_adminName_returnsFail() {
        final var result = RequestValidator.validate(new CreateProjectRequest("admin"));
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("reserved"));
    }

    @Test
    public void validate_createProject_adminPagesName_returnsFail() {
        final var result = RequestValidator
                .validate(new CreateProjectRequest(org.ccg.mpp.config.Globals.ADMIN_PAGES_PROJECT_NAME));
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("reserved"));
    }

    @Test
    public void validate_createProject_exactlyThreeChars_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateProjectRequest("abc")).isValid());
    }

    @Test
    public void validate_createProject_exactly64Chars_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateProjectRequest("a".repeat(64))).isValid());
    }

    @Test
    public void validate_dropProject_validName_returnsOk() {
        assertTrue(RequestValidator.validate(new DropProjectRequest("myProject")).isValid());
    }

    @Test
    public void validate_dropProject_adminName_returnsFail() {
        assertFalse(RequestValidator.validate(new DropProjectRequest("admin")).isValid());
    }

    @Test
    public void validate_listCollections_validProjectName_returnsOk() {
        assertTrue(RequestValidator.validate(new ListCollectionsRequest("myProject")).isValid());
    }

    @Test
    public void validate_listCollections_nullProjectName_returnsFail() {
        assertFalse(RequestValidator.validate(new ListCollectionsRequest(null)).isValid());
    }

    @Test
    public void validate_listCollections_adminAllowed_returnsOk() {
        assertTrue(RequestValidator.validate(new ListCollectionsRequest("myProject")).isValid());
    }

    @Test
    public void validate_createCollection_validNames_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateCollectionRequest("myProject", "myColl")).isValid());
    }

    @Test
    public void validate_createCollection_nullCollectionName_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateCollectionRequest("myProject", null)).isValid());
    }

    @Test
    public void validate_createCollection_adminProject_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateCollectionRequest("admin", "myColl")).isValid());
    }

    @Test
    public void validate_createCollection_invalidCollectionName_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateCollectionRequest("myProject", "ab")).isValid());
    }

    @Test
    public void validate_dropCollection_validNames_returnsOk() {
        assertTrue(RequestValidator.validate(new DropCollectionRequest("myProject", "myColl")).isValid());
    }

    @Test
    public void validate_createIndex_validFieldName_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateIndexRequest("myProject", "myColl", "myField")).isValid());
    }

    @Test
    public void validate_createIndex_blankFieldName_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateIndexRequest("myProject", "myColl", "  ")).isValid());
    }

    @Test
    public void validate_createIndex_nullFieldName_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateIndexRequest("myProject", "myColl", null)).isValid());
    }

    @Test
    public void validate_dropIndex_validFieldName_returnsOk() {
        assertTrue(RequestValidator.validate(new DropIndexRequest("myProject", "myColl", "myField")).isValid());
    }

    @Test
    public void validate_dropIndex_nullFieldName_returnsFail() {
        assertFalse(RequestValidator.validate(new DropIndexRequest("myProject", "myColl", null)).isValid());
    }

    @Test
    public void validate_reindex_noFieldNames_returnsOk() {
        assertTrue(RequestValidator.validate(new ReindexRequest("myProject", "myColl", null)).isValid());
    }

    @Test
    public void validate_reindex_withValidFieldNames_returnsOk() {
        assertTrue(RequestValidator.validate(new ReindexRequest("myProject", "myColl", List.of("email", "status")))
                .isValid());
    }

    @Test
    public void validate_reindex_primaryKey_aloneAndWithFieldNames_returnsOk() {
        assertTrue(RequestValidator.validate(new ReindexRequest("myProject", "myColl", null, true)).isValid());
        assertTrue(
                RequestValidator.validate(new ReindexRequest("myProject", "myColl", List.of("email"), true)).isValid());
    }

    @Test
    public void validate_reindex_primaryKey_blankEntryInFieldNames_returnsFail() {
        assertFalse(RequestValidator.validate(new ReindexRequest("myProject", "myColl", List.of(" "), true)).isValid());
    }

    @Test
    public void validate_reindex_blankEntryInFieldNames_returnsFail() {
        final var result = RequestValidator.validate(new ReindexRequest("myProject", "myColl", List.of("email", " ")));
        assertFalse(result.isValid());
        assertEquals("REINDEX fieldNames must not contain blank entries", result.getErrorMessage());
    }

    @Test
    public void validate_reindex_nullEntryInFieldNames_returnsFail() {
        final var names = new java.util.ArrayList<String>();
        names.add("email");
        names.add(null);
        assertFalse(RequestValidator.validate(new ReindexRequest("myProject", "myColl", names)).isValid());
    }

    @Test
    public void validate_reindex_nullProjectName_returnsFail() {
        assertFalse(RequestValidator.validate(new ReindexRequest(null, "myColl", null)).isValid());
    }

    @Test
    public void validate_reindex_adminProject_returnsFail() {
        assertFalse(RequestValidator.validate(new ReindexRequest("admin", "myColl", null)).isValid());
    }

    @Test
    public void validate_runScript_missingProjectName_returnsFail() {
        assertFalse(
                RequestValidator.validate(new org.ccg.mpp.ops.req.RunScriptRequest(null, "return 1;", null)).isValid());
    }

    @Test
    public void validate_runScript_invalidProjectName_returnsFail() {
        assertFalse(
                RequestValidator.validate(new org.ccg.mpp.ops.req.RunScriptRequest("a", "return 1;", null)).isValid());
    }

    @Test
    public void validate_runScript_adminProject_returnsFail() {
        assertFalse(RequestValidator.validate(new org.ccg.mpp.ops.req.RunScriptRequest("admin", "return 1;", null))
                .isValid());
    }

    // The history collection is the server's to write. Reads stay open - it exists to be queried.
    @Test
    public void validate_save_toReservedScriptRunsCollection_returnsFail() {
        final var request = new SaveRequest("myProject", "script_runs");
        request.setObject(new JsonObject());
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_bulkSave_toReservedScriptRunsCollection_returnsFail() {
        final var request = new BulkSaveRequest("myProject", "script_runs");
        request.setObjects(List.of(new JsonObject()));
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_delete_fromReservedScriptRunsCollection_returnsFail() {
        final var request = new DeleteRequest("myProject", "script_runs");
        request.set_id("abc");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_createCollection_withReservedScriptRunsName_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateCollectionRequest("myProject", "script_runs")).isValid());
    }

    @Test
    public void validate_dropCollection_ofReservedScriptRunsCollection_returnsFail() {
        assertFalse(RequestValidator.validate(new DropCollectionRequest("myProject", "script_runs")).isValid());
    }

    @Test
    public void validate_aggregate_overReservedScriptRunsCollection_returnsOk() {
        final var request = new AggregateRequest("myProject", "script_runs");
        request.setAggregationSteps(List.of(new CountAggregationStep()));
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_findById_inReservedScriptRunsCollection_returnsOk() {
        final var request = new FindByIdRequest("myProject", "script_runs");
        request.set_id("abc");
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_createIndex_onReservedScriptRunsCollection_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateIndexRequest("myProject", "script_runs", "outcome")).isValid());
    }

    @Test
    public void validate_createProject_clusterName_returnsFail() {
        final var result = RequestValidator.validate(new CreateProjectRequest("cluster"));
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("reserved"));
    }

    @Test
    public void validate_createProject_clusterNameAnyCase_returnsFail() {
        assertFalse(RequestValidator.validate(new CreateProjectRequest("Cluster")).isValid());
        assertFalse(RequestValidator.validate(new CreateProjectRequest("CLUSTER")).isValid());
    }

    @Test
    public void validate_dropProject_clusterName_returnsFail() {
        assertFalse(RequestValidator.validate(new DropProjectRequest("cluster")).isValid());
    }

    @Test
    public void validate_save_intoClusterProject_returnsFail() {
        final var request = new SaveRequest("cluster", "items");
        request.setObject(new JsonObject());
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_findById_inClusterProject_returnsOk() {
        final var request = new FindByIdRequest("cluster", "items");
        request.set_id("abc");
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_createCollection_namedCluster_returnsOk() {
        assertTrue(RequestValidator.validate(new CreateCollectionRequest("myProject", "cluster")).isValid());
    }
}
