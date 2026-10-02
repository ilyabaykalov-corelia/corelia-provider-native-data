package ru.corelia.providernativedata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static ru.corelia.support.Json.object;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import ru.corelia.auth.AuthContext;
import ru.corelia.config.CoreliaConfig;
import ru.corelia.observability.CoreliaObservability;
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

    private static NativeDataDocumentVersionStore store(JsonNode response) {
        var services = new StubServiceClient(response);
        return new NativeDataDocumentVersionStore(services);
    }

    private static final class StubServiceClient extends ServiceClient {
        private final JsonNode response;
        private StubServiceClient(JsonNode response) {
            super(new CoreliaConfig(new StandardEnvironment()), null,
                    new CoreliaObservability(new SimpleMeterRegistry(), ObservationRegistry.NOOP, false));
            this.response = response;
        }
        @Override public JsonNode call(String target, String path, String method, JsonNode body, AuthContext auth) { return response; }
    }
}
