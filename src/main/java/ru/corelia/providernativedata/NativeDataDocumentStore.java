package ru.corelia.providernativedata;

import static ru.corelia.support.Json.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.provider.DocumentStore;
import ru.corelia.provider.model.DocumentCreation;
import ru.corelia.provider.model.DocumentSearchRequest;
import ru.corelia.provider.model.DocumentSearchResult;
import ru.corelia.provider.model.DocumentSnapshot;
import ru.corelia.transport.ServiceClient;
import tools.jackson.databind.JsonNode;

/** Provider adapter, передающий канонические document snapshots в data-service. */
public final class NativeDataDocumentStore implements DocumentStore {
    private final ServiceClient services;

    public NativeDataDocumentStore(ServiceClient services) {
        this.services = services;
    }

    @Override
    public void create(DocumentCreation creation, AuthContext auth) {
        var attributes = object();
        creation.attributes().forEach(attributes::set);
        var body = object("documentId", creation.documentId(), "typeCode", creation.typeCode(),
                "status", creation.status(), "attributes", attributes, "createdBy", creation.createdBy(),
                "createdAt", creation.createdAt().toString(), "idempotencyKey", creation.idempotencyKey(),
                "requestHash", creation.requestHash(), "schemaVersion", 1);
        if (creation.history() != null) body.set("history", creation.history());
        services.call("data", "/internal/v1/data/documents", "POST", body, auth);
    }

    @Override
    public DocumentSearchResult search(DocumentSearchRequest request, AuthContext auth) {
        JsonNode result = services.call("data", "/internal/v1/data/documents/search", "POST",
                object("typeCode", request.typeCode(), "offset", request.offset(), "limit", request.limit(), "filter", request.filter()), auth);
        return new DocumentSearchResult(list(result.path("items")).stream().map(this::snapshot).toList(), number(result, "total", 0));
    }

    @Override
    public DocumentSnapshot get(String typeCode, String documentId, AuthContext auth) {
        JsonNode result = services.call("data", "/internal/v1/data/documents/" + encode(typeCode) + "/" + encode(documentId), "GET", null, auth);
        return snapshot(result);
    }

    private DocumentSnapshot snapshot(JsonNode source) {
        try {
            var attributes = new LinkedHashMap<String, JsonNode>();
            source.path("attributes").properties().forEach(value -> attributes.put(value.getKey(), value.getValue().deepCopy()));
            return new DocumentSnapshot(text(source, "id"), text(source, "typeCode"), text(source, "status"),
                    (int) number(source, "currentVersion", 0), attributes, text(source, "createdBy"),
                    Instant.parse(text(source, "createdAt")), text(source, "changeToken"));
        } catch (RuntimeException error) {
            throw new ApiException(502, "Сервис data вернул некорректный snapshot документа");
        }
    }
}
