package ru.corelia.providernativedata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static ru.corelia.support.Json.object;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.observability.CoreliaObservability;
import ru.corelia.provider.model.AttachmentMetadata;
import ru.corelia.provider.model.DocumentMutation;
import ru.corelia.provider.model.DocumentVersion;
import ru.corelia.provider.model.StorageReference;
import ru.corelia.transport.ServiceClient;
import tools.jackson.databind.JsonNode;

/** Проверяет различие между найденным и отсутствующим idempotency receipt. */
class NativeDataDocumentVersionStoreTest {
    private static final AuthContext AUTH = new AuthContext("token", "user", "user", "Пользователь", "", java.util.List.of(), "user");

    @Test void returnsReceiptOnlyWhenDataServiceFoundIt() {
        var found = store(object("found", true, "requestHash", "hash", "response", object("ok", true)));
        assertEquals("hash", found.receipt("key", AUTH).requestHash());

        var absent = store(object("found", false));
        assertNull(absent.receipt("key", AUTH));
    }

    @Test void sendsAtomicVersionCommitToDataService() {
        var services = new StubServiceClient(object());
        var attachment = new AttachmentMetadata("attachment-2", "attachment-1", "document-1", "file.txt", "text/plain", 4,
                2, true, Instant.parse("2026-10-02T18:00:00Z"), new StorageReference("corelia-blob://file"));
        var version = new DocumentVersion("version-2", "document-1", 2, 3, Map.of("title", object("value", "Новое")),
                "IN_WORK", Instant.EPOCH, "operator", null, List.of(attachment));
        var mutation = new DocumentMutation("document-1", "TEST", 1, "token-1", Map.of("title", object("value", "Новое")),
                version, null, attachment, null, "request-1", "hash-1", object("changeToken", "token-2"), object("action", "UPDATED"), "IN_WORK");

        new NativeDataDocumentVersionStore(services).commit(mutation, AUTH);

        assertEquals("data", services.target);
        assertEquals("POST", services.method);
        assertEquals("/internal/v1/data/documents/TEST/document-1/commits", services.path);
        assertEquals(1, services.body.path("expectedVersion").asInt());
        assertEquals("token-1", services.body.path("expectedChangeToken").asString());
        assertEquals("request-1", services.body.path("idempotencyKey").asString());
        assertEquals("hash-1", services.body.path("requestHash").asString());
        assertEquals("token-2", services.body.path("changeToken").asString());
        assertEquals("operator", services.body.path("actor").asString());
        assertEquals("UPDATED", services.body.path("history").path("action").asString());
        assertEquals(2, services.body.path("createdVersion").path("number").asInt());
        assertEquals(3, services.body.path("createdVersion").path("schemaVersion").asInt());
        assertEquals("attachment-2", services.body.path("createdVersion").path("attachments").get(0).path("id").asString());
        assertEquals("corelia-blob://file", services.body.path("createdVersion").path("attachments").get(0).path("storageReference").asString());
    }

    private static NativeDataDocumentVersionStore store(JsonNode response) {
        var services = new StubServiceClient(response);
        return new NativeDataDocumentVersionStore(services);
    }

    private static final class StubServiceClient extends ServiceClient {
        private final JsonNode response;
        private String target;
        private String path;
        private String method;
        private JsonNode body;
        private StubServiceClient(JsonNode response) {
            super(new CoreliaConfig(new StandardEnvironment()), null,
                    new CoreliaObservability(new SimpleMeterRegistry(), ObservationRegistry.NOOP, false));
            this.response = response;
        }
        @Override public JsonNode call(String target, String path, String method, JsonNode body, AuthContext auth) {
            this.target = target;
            this.path = path;
            this.method = method;
            this.body = body;
            return response;
        }
    }
}
