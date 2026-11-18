package io.github.christiancg.mpp.client.admin;

import io.github.christiancg.mpp.client.Document;
import java.util.List;
import java.util.Map;

public record UserInfo(String username, boolean admin, List<String> globalPermissions,
        Map<String, Object> databasePermissions, Map<String, Object> collectionPermissions,
        Map<String, Object> scriptPermissions, List<String> ownedDatabases, Document raw) {
}
