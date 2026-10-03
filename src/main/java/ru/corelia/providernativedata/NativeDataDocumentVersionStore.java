package ru.corelia.providernativedata;

import static ru.corelia.support.Json.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.provider.DocumentVersionStore;
import ru.corelia.provider.model.AttachmentMetadata;
import ru.corelia.provider.model.DocumentMutation;
import ru.corelia.provider.model.DocumentSnapshot;
import ru.corelia.provider.model.DocumentVersion;
import ru.corelia.provider.model.DocumentVersionState;
import ru.corelia.provider.model.IdempotencyReceipt;
import ru.corelia.provider.model.StorageReference;
import ru.corelia.transport.ServiceClient;
import tools.jackson.databind.JsonNode;

/** Read-side адаптер неизменяемых версий из data-service. */
public final class NativeDataDocumentVersionStore implements DocumentVersionStore {
    private final ServiceClient services;

    public NativeDataDocumentVersionStore(ServiceClient services) {
        this.services = services;
    }

    @Override public String documentType(String id, AuthContext auth) {
        return text(call("/by-id/" + encode(id) + "/type", auth), "typeCode");
    }
    @Override public List<DocumentVersion> documentVersions(String id, AuthContext auth) {
        return list(call("/by-id/" + encode(id) + "/versions", auth).path("items")).stream().map(this::version).toList();
    }
    @Override public List<AttachmentMetadata> attachments(String id, AuthContext auth) {
        return list(call("/by-id/" + encode(id) + "/attachments", auth).path("items")).stream().map(this::attachment).toList();
    }
    @Override public DocumentVersionState state(String type, String id, AuthContext auth) {
        JsonNode raw = call("/" + encode(type) + "/" + encode(id) + "/state", auth);
        List<DocumentVersion> versions = list(raw.path("versions")).stream().map(this::version).toList();
        DocumentVersion current = version(raw.path("currentVersion"));
        return new DocumentVersionState(snapshot(raw.path("document")), current, versions, list(raw.path("attachments")).stream().map(this::attachment).toList());
    }
    @Override public IdempotencyReceipt receipt(String key, AuthContext auth) {
        JsonNode raw = call("/receipts/" + encode(key), auth);
        return raw.path("found").asBoolean() ? new IdempotencyReceipt(text(raw, "requestHash"), raw.path("response")) : null;
    }
    @Override public List<JsonNode> history(String id, AuthContext auth) { return list(call("/by-id/" + encode(id) + "/history", auth).path("items")); }
    @Override public void commit(DocumentMutation mutation, AuthContext auth) {
        var attributes = object(); mutation.attributes().forEach(attributes::set);
        String changeToken = text(mutation.response(), "changeToken");
        if (changeToken.isEmpty()) changeToken = java.util.UUID.randomUUID().toString();
        var body = object("expectedVersion", mutation.expectedVersion(), "expectedChangeToken", mutation.expectedChangeToken(),
                "attributes", attributes, "status", mutation.status(), "idempotencyKey", mutation.idempotencyKey(),
                "requestHash", mutation.requestHash(), "response", mutation.response(), "history", mutation.history(),
                "changeToken", changeToken, "actor", mutation.createdVersion() == null ? auth.login() : mutation.createdVersion().createdBy());
        if (mutation.createdVersion() != null) {
            DocumentVersion version = mutation.createdVersion();
            var attachments = array(version.attachments().stream().map(this::attachmentJson).toList());
            body.set("createdVersion", object("id", version.id().isEmpty() ? java.util.UUID.randomUUID().toString() : version.id(), "number", version.number(), "schemaVersion", version.schemaVersion(), "createdBy", version.createdBy(), "attachments", attachments));
        }
        if (mutation.createdAttachment() != null)
            body.set("createdAttachment", attachmentJson(mutation.createdAttachment()));
        if (mutation.retiredAttachment() != null)
            body.put("retiredAttachmentId", mutation.retiredAttachment().id());
        services.call("data", "/internal/v1/data/documents/" + encode(mutation.documentType()) + "/" + encode(mutation.documentId()) + "/commits", "POST", body, auth);
    }
    private JsonNode call(String path, AuthContext auth) { return services.call("data", "/internal/v1/data/documents" + path, "GET", null, auth); }
    private DocumentSnapshot snapshot(JsonNode v) { var a = new LinkedHashMap<String, JsonNode>(); v.path("attributes").properties().forEach(e -> a.put(e.getKey(), e.getValue())); return new DocumentSnapshot(text(v,"id"), text(v,"typeCode"), text(v,"status"), (int) number(v,"currentVersion",0), a, text(v,"createdBy"), Instant.parse(text(v,"createdAt")), text(v,"changeToken")); }
    private DocumentVersion version(JsonNode v) { var a = new LinkedHashMap<String, JsonNode>(); v.path("attributes").properties().forEach(e -> a.put(e.getKey(), e.getValue())); return new DocumentVersion(text(v,"id"), text(v,"documentId"), (int) number(v,"number",0), (int) number(v,"schemaVersion",0), a, text(v,"status"), Instant.parse(text(v,"createdAt")), text(v,"createdBy"), text(v,"closedAt").isEmpty()?null:Instant.parse(text(v,"closedAt")), list(v.path("attachments")).stream().map(this::attachment).toList()); }
    private AttachmentMetadata attachment(JsonNode v) { return new AttachmentMetadata(text(v,"id"), text(v,"logicalId"), text(v,"documentId"), text(v,"fileName"), text(v,"contentType"), number(v,"size",0), number(v,"version",1), v.path("current").asBoolean(true), text(v,"uploadedAt").isEmpty()?null:Instant.parse(text(v,"uploadedAt")), new StorageReference(text(v,"storageReference"))); }
    private JsonNode attachmentJson(AttachmentMetadata value) { return object("id", value.id(), "logicalId", value.logicalId(), "documentId", value.documentId(), "fileName", value.fileName(), "contentType", value.contentType(), "size", value.size(), "version", value.version(), "storageReference", value.storageReference().value(), "uploadedAt", value.uploadedAt() == null ? "" : value.uploadedAt().toString()); }
}
