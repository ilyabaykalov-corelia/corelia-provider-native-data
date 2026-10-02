package ru.corelia.providernativedata;

import java.util.EnumSet;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import ru.corelia.provider.ProviderCapability;
import ru.corelia.provider.ProviderDescriptor;
import ru.corelia.provider.DocumentStore;
import ru.corelia.provider.DocumentTypeProvider;
import ru.corelia.provider.DocumentVersionStore;
import ru.corelia.transport.ServiceClient;
import ru.corelia.configuration.DocumentTypeCatalog;

/** Регистрирует нативное хранилище данных при выборе соответствующих capability. */
@AutoConfiguration
@ConditionalOnExpression("'${corelia.provider:platform-v}' == 'native-data' || "
        + "'${corelia.provider.documents:}' == 'native-data' || "
        + "'${corelia.provider.document-versions:}' == 'native-data' || "
        + "'${corelia.provider.document-types:}' == 'native-data' || "
        + "'${corelia.provider.attachments:}' == 'native-data'")
public class NativeDataProviderConfiguration {
    @Bean
    DocumentStore nativeDataDocumentStore(ServiceClient services) {
        return new NativeDataDocumentStore(services);
    }

    @Bean
    DocumentTypeProvider nativeDataDocumentTypeProvider(DocumentTypeCatalog types) {
        return new NativeDataDocumentTypeProvider(types);
    }

    @Bean
    DocumentVersionStore nativeDataDocumentVersionStore(ServiceClient services, DocumentStore store) {
        return new NativeDataDocumentVersionStore(services, (NativeDataDocumentStore) store);
    }

    @Bean
    ProviderDescriptor nativeDataProviderDescriptor() {
        return new ProviderDescriptor() {
            @Override public String id() { return "native-data"; }
            @Override public java.util.Set<ProviderCapability> capabilities() {
                return EnumSet.of(
                        ProviderCapability.DOCUMENTS,
                        ProviderCapability.DOCUMENT_VERSIONS,
                        ProviderCapability.DOCUMENT_TYPES,
                        ProviderCapability.ATTACHMENTS);
            }
        };
    }
}
