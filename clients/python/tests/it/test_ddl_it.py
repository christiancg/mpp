from __future__ import annotations

import pytest

from mpp import AsyncMppClient, ConflictError, MppClient, NotFoundError

pytestmark = pytest.mark.it


async def test_projects_and_collections_are_created_listed_and_dropped(client: AsyncMppClient) -> None:
    shop = client.project("ddl_it")
    await shop.create()
    with pytest.raises(ConflictError):
        await shop.create()
    assert "ddl_it" in await client.list_projects()
    orders = shop.collection("orders")
    await orders.create()
    assert await shop.list_collections() == ("orders",)
    await orders.drop()
    assert await shop.list_collections() == ()
    await shop.drop()
    assert "ddl_it" not in await client.list_projects()


async def test_a_save_into_a_missing_collection_is_refused(client: AsyncMppClient) -> None:
    shop = client.project("ddl_missing")
    await shop.create()
    with pytest.raises(NotFoundError) as refused:
        await shop.collection("nothing").save({"a": 1})
    assert refused.value.raw_code == "404-11"


def test_the_sync_client_runs_the_same_ddl(sync_client: MppClient) -> None:
    shop = sync_client.project("ddl_sync")
    shop.create()
    shop.collection("orders").create()
    assert shop.list_collections() == ("orders",)
    shop.drop()
    assert "ddl_sync" not in sync_client.list_projects()
