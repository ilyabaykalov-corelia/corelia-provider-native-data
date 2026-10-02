package ru.corelia.providernativedata;

import static ru.corelia.support.Json.*;

import java.time.Instant;
import java.util.List;
import ru.corelia.auth.AuthContext;
import ru.corelia.provider.AttachmentCatalog;
import ru.corelia.provider.model.AttachmentMetadata;
import ru.corelia.provider.model.StorageReference;
import ru.corelia.transport.ServiceClient;
import tools.jackson.databind.JsonNode;

/** Читает metadata вложений из native data-service. */
public final class NativeDataAttachmentCatalog implements AttachmentCatalog {
    private final ServiceClient services;
    public NativeDataAttachmentCatalog(ServiceClient services) { this.services = services; }
    @Override public AttachmentMetadata find(String id, AuthContext auth) { return attachment(call("/attachments/" + encode(id), auth)); }
    @Override public List<AttachmentMetadata> attachmentVersions(String id, AuthContext auth) { return list(call("/attachments/" + encode(id) + "/versions", auth).path("items")).stream().map(this::attachment).toList(); }
    private JsonNode call(String path, AuthContext auth) { return services.call("data", "/internal/v1/data/documents" + path, "GET", null, auth); }
    private AttachmentMetadata attachment(JsonNode v) { return new AttachmentMetadata(text(v,"id"), text(v,"logicalId"), text(v,"documentId"), text(v,"fileName"), text(v,"contentType"), number(v,"size",0), number(v,"version",1), v.path("current").asBoolean(true), text(v,"uploadedAt").isEmpty()?null:Instant.parse(text(v,"uploadedAt")), new StorageReference(text(v,"storageReference"))); }
}
