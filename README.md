# corelia-provider-native-data

Адаптер document-related SPI к внутреннему API `corelia-data-service`. Это
библиотека, подключаемая к gateway, document- и attachment-service, а не
отдельное приложение и не прямой JDBC-клиент базы data-service.

## Capability

Provider с id `native-data` реализует `documents`, `document-versions`,
`document-types` и `attachments`. Он преобразует внутренние JSON-ответы data
service в canonical `DocumentSnapshot`, `DocumentVersion` и
`AttachmentMetadata`; ошибки некорректного snapshot преобразуются в 502.
Типы документов берутся из `DocumentTypeCatalog` загруженного customer
configuration.

Auto-configuration активируется, когда соответствующая
`corelia.provider.<capability>` выбрана как `native-data`. Для работы требуется
настроенный `ServiceClient`, доступный внутренний endpoint data-service и
конфигурационный каталог. Аутентификационный контекст передаётся в каждый
вызов; provider не обходит mTLS/JWT-проверки владельца данных.

```bash
mvn -pl corelia-provider-native-data -am test
```

Контракт и полный состав capability: [SPI](../docs/provider-spi.md).
