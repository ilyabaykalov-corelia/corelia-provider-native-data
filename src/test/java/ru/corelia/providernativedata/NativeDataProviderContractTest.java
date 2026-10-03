package ru.corelia.providernativedata;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static ru.corelia.support.Json.object;
import static ru.corelia.support.Json.parse;
import static ru.corelia.support.Json.write;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import liquibase.integration.spring.SpringLiquibase;
import ru.corelia.auth.AuthContext;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.data.DataDocumentService;
import ru.corelia.provider.test.InMemoryTestProvider;
import ru.corelia.provider.tck.ProviderContractTest;
import ru.corelia.provider.tck.ProviderFixture;
import ru.corelia.provider.model.DocumentSnapshot;
import ru.corelia.provider.model.DocumentMutation;
import ru.corelia.provider.model.DocumentVersion;
import ru.corelia.provider.model.AttachmentMetadata;
import ru.corelia.provider.model.StorageReference;
import ru.corelia.provider.model.WorkflowTask;
import ru.corelia.transport.ServiceClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Применяет общий Provider TCK к native data adapters и production PostgreSQL schema. */
@Testcontainers
class NativeDataProviderContractTest extends ProviderContractTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static JdbcClient jdbc;
    private static DataDocumentService data;
    private static DocumentTypeCatalog types;

    @BeforeAll static void prepareDatabase() throws Exception {
        var source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        var liquibase = new SpringLiquibase();
        liquibase.setDataSource(source);
        liquibase.setChangeLog("classpath:db/changelog/data-master.yaml");
        liquibase.afterPropertiesSet();
        jdbc = JdbcClient.create(source);
        types = mock(DocumentTypeCatalog.class);
        when(types.types()).thenReturn(List.of("TEST"));
        when(types.name("TEST")).thenReturn("Test");
        when(types.fields("TEST")).thenReturn(List.of("title"));
        data = new DataDocumentService(jdbc, types);
    }

    @Override protected ProviderFixture fixture() {
        jdbc.sql("truncate table document cascade").update();
        jdbc.sql("truncate table outbox_event").update();
        var support = new InMemoryTestProvider();
        var allowed = new AuthContext("token", "user", "user", "Пользователь", "", List.of("EDITOR"), "user");
        var denied = new AuthContext("token", "denied", "denied", "Нет доступа", "", List.of("DENY"), "denied");
        var services = new LocalDataServiceClient(data);
        var documents = new NativeDataDocumentStore(services);
        var versions = new NativeDataDocumentVersionStore(services);
        var attachments = new NativeDataAttachmentCatalog(services);
        var documentTypes = new NativeDataDocumentTypeProvider(types);
        return new ProviderFixture() {
            @Override public ru.corelia.provider.DocumentStore documents() { return documents; }
            @Override public ru.corelia.provider.DocumentVersionStore versions() { return versions; }
            @Override public ru.corelia.provider.DocumentTypeProvider documentTypes() { return documentTypes; }
            @Override public ru.corelia.provider.BinaryStorage storage() { return support; }
            @Override public ru.corelia.provider.AttachmentCatalog attachments() { return attachments; }
            @Override public ru.corelia.provider.WorkflowProvider workflows() { return support; }
            @Override public ru.corelia.provider.TaskProvider tasks() { return support; }
            @Override public ru.corelia.provider.PermissionProvider permissions() { return support; }
            @Override public AuthContext allowedAuth() { return allowed; }
            @Override public AuthContext deniedAuth() { return denied; }
            @Override public void seed(DocumentSnapshot document, DocumentVersion version) { seedDocument(document, version); }
            @Override public void seed(WorkflowTask task) { support.seed(task); }
        };
    }

    @Test void persistsAttachmentMutationWithoutCreatingDocumentVersion() {
        var local = fixture();
        var values = local.data();
        local.seed(new DocumentSnapshot(
                values.documentId(), values.documentType(), "DRAFT", 1,
                Map.of(values.attribute(), object("value", "исходное")), "user", java.time.Instant.EPOCH,
                values.changeToken()),
                new DocumentVersion("version-1", values.documentId(), 1, 1,
                        Map.of(values.attribute(), object("value", "исходное")), "DRAFT", java.time.Instant.EPOCH,
                        "user", null, List.of()));
        var attachment = new AttachmentMetadata(
                "attachment-1", "attachment-1", values.documentId(), "file.txt", "text/plain", 4, 1,
                true, java.time.Instant.EPOCH, new StorageReference("s3://bucket/attachment-1"));

        local.versions().commit(new DocumentMutation(
                values.documentId(), values.documentType(), 1, values.changeToken(),
                Map.of(values.attribute(), object("value", "исходное")), null, null, attachment, null,
                "attachment-only", "attachment-only-hash", object("id", attachment.id())), local.allowedAuth());

        assertEquals(attachment.id(), local.attachments().find(attachment.id(), local.allowedAuth()).id());
        assertEquals(attachment.id(), local.versions().attachments(values.documentId(), local.allowedAuth()).getFirst().id());
    }

    private static void seedDocument(DocumentSnapshot document, DocumentVersion version) {
        ObjectNode attributes = (ObjectNode) object();
        document.attributes().forEach(attributes::set);
        Timestamp createdAt = Timestamp.from(document.createdAt());
        jdbc.sql("insert into document (id, type_code, status, current_version, change_token, attributes, created_by, created_at, updated_by, updated_at) values (:id, :type, :status, :number, :token, cast(:attributes as jsonb), :actor, :at, :actor, :at)")
                .param("id", document.id()).param("type", document.typeCode()).param("status", document.status())
                .param("number", document.currentVersion()).param("token", document.changeToken()).param("attributes", write(attributes))
                .param("actor", document.createdBy()).param("at", createdAt).update();
        ObjectNode versionAttributes = (ObjectNode) object();
        version.attributes().forEach(versionAttributes::set);
        jdbc.sql("insert into document_version (id, document_id, version_no, schema_version, status, attributes, created_by, created_at) values (:id, :documentId, :number, :schemaVersion, :status, cast(:attributes as jsonb), :actor, :at)")
                .param("id", version.id()).param("documentId", document.id()).param("number", version.number())
                .param("schemaVersion", version.schemaVersion()).param("status", version.status()).param("attributes", write(versionAttributes))
                .param("actor", version.createdBy()).param("at", Timestamp.from(version.createdAt())).update();
    }

    private static final class LocalDataServiceClient extends ServiceClient {
        private final DataDocumentService service;
        LocalDataServiceClient(DataDocumentService service) { super(null, null, null); this.service = service; }
        @Override public JsonNode call(String target, String path, String method, JsonNode body, AuthContext auth) {
            if (!"data".equals(target) || !path.startsWith("/internal/v1/data/documents")) throw new IllegalArgumentException("Unexpected service call");
            // Повторяем JSON-границу реального HTTP-клиента, а не передаём изменяемый узел напрямую.
            body = body == null ? null : parse(write(body));
            String value = path.substring("/internal/v1/data/documents".length());
            if ("POST".equals(method) && value.isEmpty()) return service.create(body);
            if ("POST".equals(method) && "/search".equals(value)) return service.search(body);
            String[] parts = value.substring(1).split("/");
            if ("POST".equals(method) && parts.length == 3 && "commits".equals(parts[2])) return service.commit(parts[0], parts[1], body);
            if ("GET".equals(method) && parts.length == 3 && "by-id".equals(parts[0]) && "type".equals(parts[2])) return object("typeCode", service.type(parts[1]));
            if ("GET".equals(method) && parts.length == 3 && "by-id".equals(parts[0]) && "versions".equals(parts[2])) return service.versions(parts[1]);
            if ("GET".equals(method) && parts.length == 3 && "by-id".equals(parts[0]) && "attachments".equals(parts[2])) return service.attachments(parts[1]);
            if ("GET".equals(method) && parts.length == 3 && "by-id".equals(parts[0]) && "history".equals(parts[2])) return service.history(parts[1]);
            if ("GET".equals(method) && parts.length == 2 && "receipts".equals(parts[0])) return service.receipt(parts[1]);
            if ("GET".equals(method) && parts.length == 3 && "attachments".equals(parts[0]) && "versions".equals(parts[2])) return service.attachmentVersions(parts[1]);
            if ("GET".equals(method) && parts.length == 2 && "attachments".equals(parts[0])) return service.attachment(parts[1]);
            if ("GET".equals(method) && parts.length == 3 && "state".equals(parts[2])) return service.state(parts[0], parts[1]);
            if ("GET".equals(method) && parts.length == 2) return service.get(parts[0], parts[1]);
            throw new IllegalArgumentException("Unexpected data path: " + path);
        }
    }
}
