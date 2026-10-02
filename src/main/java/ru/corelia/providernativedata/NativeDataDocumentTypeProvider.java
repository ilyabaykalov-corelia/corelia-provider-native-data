package ru.corelia.providernativedata;

import java.util.List;
import ru.corelia.auth.AuthContext;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.provider.DocumentTypeProvider;
import ru.corelia.provider.model.AvailableDocumentType;

/** Отдаёт настроенные заказчиком виды документов без зависимости от data provider-а. */
public final class NativeDataDocumentTypeProvider implements DocumentTypeProvider {
    private final DocumentTypeCatalog types;

    public NativeDataDocumentTypeProvider(DocumentTypeCatalog types) {
        this.types = types;
    }

    @Override
    public List<AvailableDocumentType> available(AuthContext auth) {
        return types.types().stream().map(type -> new AvailableDocumentType(type, types.name(type))).toList();
    }
}
