from __future__ import annotations

from collections.abc import Sequence

from mpp._internal.pipeline_encoder import PipelineEncoder
from mpp._internal.values_codec import NO_ZONE, EncodeOptions, Json, JsonObject, encode_document
from mpp.query.types import Filter, PipelineStep


def request(kind: str, project: str | None = None, collection: str | None = None) -> JsonObject:
    frame: JsonObject = {"type": kind}
    if project is not None:
        frame["projectName"] = project
    if collection is not None:
        frame["collectionName"] = collection
    return frame


def _dirty(frame: JsonObject, dirty_read: bool) -> JsonObject:
    return {**frame, "dirtyRead": True} if dirty_read else frame


class RequestEncoder:
    __slots__ = ("_options", "pipeline")

    def __init__(self, options: EncodeOptions = NO_ZONE) -> None:
        self._options = options
        self.pipeline = PipelineEncoder(options)

    def document(self, value: object) -> JsonObject:
        return encode_document(value, self._options)

    def authenticate(self, username: str, password: str) -> JsonObject:
        return {**request("AUTHENTICATE"), "username": username, "password": password}

    def save(self, project: str, collection: str, document: JsonObject) -> JsonObject:
        return {**request("SAVE", project, collection), "object": document}

    def insert(self, project: str, collection: str, document: JsonObject) -> JsonObject:
        return {**request("SAVE", project, collection), "failOnUpdate": True, "object": document}

    def bulk_save(self, project: str, collection: str, documents: list[JsonObject]) -> JsonObject:
        objects: list[Json] = list(documents)
        return {**request("BULK_SAVE", project, collection), "objects": objects}

    def find_by_id(self, project: str, collection: str, document_id: str, dirty_read: bool) -> JsonObject:
        return _dirty({**request("FIND_BY_ID", project, collection), "_id": document_id}, dirty_read)

    def delete(self, project: str, collection: str, document_id: str) -> JsonObject:
        return {**request("DELETE", project, collection), "_id": document_id}

    def update_where(self, project: str, collection: str, condition: Filter, patch: JsonObject) -> JsonObject:
        return {**request("SAVE", project, collection), "condition": self.pipeline.filter(condition), "object": patch}

    def delete_where(self, project: str, collection: str, condition: Filter) -> JsonObject:
        return {**request("DELETE", project, collection), "condition": self.pipeline.filter(condition)}

    def aggregate(
        self,
        project: str,
        collection: str,
        steps: Sequence[PipelineStep],
        dirty_read: bool,
        analyze: bool,
    ) -> JsonObject:
        frame = {**request("AGGREGATE", project, collection), "aggregationSteps": self.pipeline.steps(steps)}
        return _dirty({**frame, "analyze": True} if analyze else frame, dirty_read)

    def listen(self, project: str, collection: str, steps: Sequence[PipelineStep]) -> JsonObject:
        return {**request("LISTEN", project, collection), "aggregationSteps": self.pipeline.steps(steps)}

    def stop_listen(self, listen_id: str) -> JsonObject:
        return {**request("STOP_LISTEN"), "listenId": listen_id}

    def list_projects(self) -> JsonObject:
        return request("LIST_PROJECTS")

    def create_project(self, project: str) -> JsonObject:
        return request("CREATE_PROJECT", project)

    def drop_project(self, project: str) -> JsonObject:
        return request("DROP_PROJECT", project)

    def list_collections(self, project: str) -> JsonObject:
        return request("LIST_COLLECTIONS", project)

    def create_collection(self, project: str, collection: str) -> JsonObject:
        return request("CREATE_COLLECTION", project, collection)

    def drop_collection(self, project: str, collection: str) -> JsonObject:
        return request("DROP_COLLECTION", project, collection)

    def create_index(self, project: str, collection: str, fields: Sequence[str]) -> JsonObject:
        frame = request("CREATE_INDEX", project, collection)
        if len(fields) == 1:
            return {**frame, "fieldName": fields[0]}
        names: list[Json] = list(fields)
        return {**frame, "fieldNames": names}

    def drop_index(self, project: str, collection: str, field: str) -> JsonObject:
        return {**request("DROP_INDEX", project, collection), "fieldName": field}

    def reindex(self, project: str, collection: str, fields: Sequence[str]) -> JsonObject:
        frame = request("REINDEX", project, collection)
        names: list[Json] = list(fields)
        return {**frame, "fieldNames": names} if names else frame

    def reindex_primary_key(self, project: str, collection: str) -> JsonObject:
        return {**request("REINDEX", project, collection), "primaryKey": True}

    def save_schema(self, project: str, collection: str, schema: JsonObject) -> JsonObject:
        return {**request("SAVE_SCHEMA", project, collection), "schema": schema}

    def get_schema(self, project: str, collection: str) -> JsonObject:
        return request("GET_SCHEMA", project, collection)

    def delete_schema(self, project: str, collection: str) -> JsonObject:
        return request("DELETE_SCHEMA", project, collection)

    def start_transaction(self) -> JsonObject:
        return request("START_TRANSACTION")

    def commit_transaction(self) -> JsonObject:
        return request("COMMIT_TRANSACTION")

    def rollback_transaction(self) -> JsonObject:
        return request("ROLLBACK_TRANSACTION")

    def server_info(self) -> JsonObject:
        return request("SERVER_INFO")

    def project_stats(self, project: str | None = None) -> JsonObject:
        return request("GET_PROJECT_STATS", project)

    def close_connection(self) -> JsonObject:
        return request("CLOSE_CONNECTION")

    def set_project_owners(self, project: str, owners: Sequence[str]) -> JsonObject:
        names: list[Json] = list(owners)
        return {**request("SET_PROJECT_OWNERS", project), "owners": names}
