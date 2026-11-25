package io.github.christiancg.mpp.client;

import io.github.christiancg.mpp.client.files.ListOptions;
import io.github.christiancg.mpp.client.files.WriteMode;
import io.github.christiancg.mpp.client.files.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

final class WireFixtureFileCases {
    private WireFixtureFileCases() {
    }

    static void add(Map<String, Consumer<Recorder>> cases) {
        cases.put("files.write_file",
                r -> r.shop.files().write("/public/index.html", "<h1>hi</h1>".getBytes(StandardCharsets.UTF_8)));
        cases.put("files.write_file_text", r -> r.shop.files().writeText("/logs/app.log", "started\n",
                WriteOptions.builder().mode(WriteMode.APPEND).build()));
        cases.put("files.write_file_conditional",
                r -> r.shop.files().write("/config.json", "{}".getBytes(StandardCharsets.UTF_8),
                        WriteOptions.builder().mode(WriteMode.CREATE).createParents(false).ifAbsent().build()));
        cases.put("files.read_file", r -> r.shop.files().readRange("/public/index.html", 0, 1_048_576));
        cases.put("files.read_file_text", r -> r.shop.files().readTextRange("/logs/app.log", 8, 4096));
        cases.put("files.stat_file", r -> r.shop.files().stat("/public/index.html"));
        cases.put("files.list_files",
                r -> r.shop.files().list("/public",
                        ListOptions.builder().recursive(true).pageSize(1000).startAfter("/public/app.js").build())
                        .forEach(info -> {
                        }));
        cases.put("files.delete_file", r -> r.shop.files().delete("/public", true));
        cases.put("files.move_file", r -> r.shop.files().move("/public", "/www"));
        cases.put("files.make_directory", r -> r.shop.files().makeDirectory("/assets/img"));
    }
}
