package io.github.christiancg.mpp.client;

import io.github.christiancg.mpp.client.admin.Admin;
import io.github.christiancg.mpp.client.admin.ServerInfo;
import io.github.christiancg.mpp.client.internal.Backoff;
import io.github.christiancg.mpp.client.internal.ClientContext;
import io.github.christiancg.mpp.client.internal.ConnectionFactory;
import io.github.christiancg.mpp.client.internal.ConnectionPool;
import io.github.christiancg.mpp.client.internal.Descriptors;
import io.github.christiancg.mpp.client.internal.Json;
import io.github.christiancg.mpp.client.internal.Listens;
import io.github.christiancg.mpp.client.internal.PooledChannel;
import io.github.christiancg.mpp.client.internal.RequestEncoder;
import io.github.christiancg.mpp.client.internal.ResponseDecoder;
import io.github.christiancg.mpp.client.internal.ScriptRequestEncoder;
import io.github.christiancg.mpp.client.mapping.JacksonCodec;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.util.List;
import java.util.Objects;

public final class MppClient implements AutoCloseable {
    private final ClientSettings settings;
    private final ConnectionPool pool;
    private final Listens listens;
    private final ClientContext context;

    private MppClient(ClientSettings settings, Backoff backoff) {
        this.settings = settings;
        final var json = new Json(settings);
        final var encoder = new RequestEncoder(json);
        final var factory = new ConnectionFactory(settings, json, encoder);
        this.pool = new ConnectionPool(factory);
        this.listens = new Listens(factory, encoder, settings.requestTimeout());
        this.context = new ClientContext(
                new PooledChannel(pool, settings.retryPolicy(), settings.requestTimeout(), backoff), encoder,
                new ScriptRequestEncoder(encoder.pipeline()), json, listens, settings.username(),
                settings.requestTimeout());
    }

    public static MppClient connect(ClientSettings settings) {
        return connect(settings, Backoff.sleeping());
    }

    static MppClient connect(ClientSettings settings, Backoff backoff) {
        final var client = new MppClient(Objects.requireNonNull(settings, "settings"), backoff);
        try {
            client.pool.borrow().close();
            return client;
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    public ClientSettings settings() {
        return settings;
    }

    public Database database(String name) {
        return new Database(context, name);
    }

    public List<String> listDatabases() {
        final var reply = context.channel().send(Operation.LIST_DATABASES, context.encoder().listDatabases());
        return ResponseDecoder.strings(reply.get("databases"));
    }

    public ServerInfo serverInfo() {
        final var reply = context.channel().send(Operation.SERVER_INFO, context.encoder().serverInfo());
        return Descriptors.server(ResponseDecoder.document(reply, "server").orElseGet(Document::empty));
    }

    public Document databaseStats() {
        final var reply = context.channel().send(Operation.GET_DATABASE_STATS, context.encoder().databaseStats());
        return ResponseDecoder.document(reply, "stats").orElseGet(Document::empty);
    }

    public void changePassword(String currentPassword, String newPassword) {
        final var username = context.username()
                .orElseThrow(() -> new IllegalStateException("the client has no credentials to change"));
        context.channel().send(Operation.SET_PASSWORD,
                context.scripts().setPassword(username, currentPassword, newPassword));
    }

    public <T> DocumentCodec<T> codecFor(Class<T> type) {
        return new JacksonCodec<>(Objects.requireNonNull(type, "type"), context.json());
    }

    public Admin admin() {
        return new Admin(context);
    }

    public Transaction beginTransaction() {
        return new Transaction(pool.borrow(), context);
    }

    public <R> R transaction(TransactionBody<R> body) {
        Objects.requireNonNull(body, "body");
        try (var transaction = beginTransaction()) {
            final var result = body.run(transaction);
            transaction.commit();
            return result;
        }
    }

    @Override
    public void close() {
        listens.close();
        pool.close();
    }
}
