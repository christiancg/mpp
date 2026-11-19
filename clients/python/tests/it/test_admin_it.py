from __future__ import annotations

import pytest

from mpp import (
    AsyncMppClient,
    AuthenticationError,
    Credentials,
    DataPermission,
    ForbiddenError,
    Permissions,
    field,
)
from tests.it.conftest import settings_for
from tests.support.server_process import ServerProcess

pytestmark = pytest.mark.it
BOB_PASSWORD = "Bob-Secret-1234"


async def _as_bob(server: ServerProcess, password: str = BOB_PASSWORD) -> AsyncMppClient:
    return await AsyncMppClient.connect(settings_for(server, credentials=Credentials("bob", password)))


async def test_users_permissions_owners_and_passwords(server: ServerProcess, client: AsyncMppClient) -> None:
    await client.project("admin_it").create()
    await client.project("admin_it").collection("orders").create()
    users = client.admin().users()
    await users.create("bob", BOB_PASSWORD, Permissions(projects={"admin_it": DataPermission.READ}))
    async with await _as_bob(server) as bob:
        orders = bob.project("admin_it").collection("orders")
        assert await orders.query().to_list() == []
        with pytest.raises(ForbiddenError) as refused:
            await orders.save({"_id": "x"})
        assert str(refused.value.raw_code).startswith("403")
        with pytest.raises(ForbiddenError):
            await bob.admin().users().list()
        await bob.change_password(BOB_PASSWORD, "Bob-Secret-5678")
    with pytest.raises(AuthenticationError):
        await _as_bob(server)
    await users.change_permissions("bob", Permissions(projects={"admin_it": DataPermission.READ_WRITE}))
    await users.set_password("bob", BOB_PASSWORD)
    async with await _as_bob(server) as writer:
        await writer.project("admin_it").collection("orders").save({"_id": "y"})
    await client.project("admin_it").set_owners(["bob"])
    listed = await users.list(field("_id").eq("bob"))
    assert len(listed) == 1
    assert listed[0].owned_projects == ("admin_it",)
    assert len(await users.list()) >= 2
    await users.delete("bob")
    assert await users.list(field("_id").eq("bob")) == []


async def test_server_info_stats_and_transactions(client: AsyncMppClient) -> None:
    info = await client.server_info()
    assert info.state == "READY"
    assert info.version
    assert "node" in info.raw
    await client.project("stats_it").create()
    assert await client.project_stats()
    assert isinstance(await client.project("stats_it").stats(), dict)
    assert await client.admin().transactions().list() == []
