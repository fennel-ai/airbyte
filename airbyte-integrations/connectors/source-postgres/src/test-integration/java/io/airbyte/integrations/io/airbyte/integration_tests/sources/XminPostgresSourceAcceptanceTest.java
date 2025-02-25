package io.airbyte.integrations.io.airbyte.integration_tests.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.airbyte.commons.features.EnvVariableFeatureFlags;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.Database;
import io.airbyte.db.factory.DSLContextFactory;
import io.airbyte.db.factory.DatabaseDriver;
import io.airbyte.db.jdbc.JdbcUtils;
import io.airbyte.integrations.standardtest.source.TestDestinationEnv;
import io.airbyte.integrations.util.HostPortResolver;
import io.airbyte.protocol.models.Field;
import io.airbyte.protocol.models.JsonSchemaType;
import io.airbyte.protocol.models.v0.CatalogHelpers;
import io.airbyte.protocol.models.v0.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.v0.ConfiguredAirbyteStream;
import io.airbyte.protocol.models.v0.DestinationSyncMode;
import io.airbyte.protocol.models.v0.SyncMode;
import java.util.HashMap;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

@ExtendWith(SystemStubsExtension.class)
public class XminPostgresSourceAcceptanceTest extends AbstractPostgresSourceAcceptanceTest {

  private static final String STREAM_NAME = "id_and_name";
  private static final String STREAM_NAME2 = "starships";
  private static final String STREAM_NAME_MATERIALIZED_VIEW = "testview";
  private static final String SCHEMA_NAME = "public";
  @SystemStub
  private EnvironmentVariables environmentVariables;

  private PostgreSQLContainer<?> container;
  private JsonNode config;
  private Database database;
  private ConfiguredAirbyteCatalog configCatalog;

  @Override
  protected JsonNode getConfig() throws Exception {
    return config;
  }

  @Override
  protected void setupEnvironment(final TestDestinationEnv environment) throws Exception {
    environmentVariables.set(EnvVariableFeatureFlags.USE_STREAM_CAPABLE_STATE, "true");

    container = new PostgreSQLContainer<>("postgres:13-alpine");
    container.start();
    final String username = container.getUsername();
    final String password = container.getPassword();
    final List<String> schemas = List.of("public");
    config = getXminConfig(username, password, schemas);
    try (final DSLContext dslContext = DSLContextFactory.create(
        config.get(JdbcUtils.USERNAME_KEY).asText(),
        config.get(JdbcUtils.PASSWORD_KEY).asText(),
        DatabaseDriver.POSTGRESQL.getDriverClassName(),
        String.format(DatabaseDriver.POSTGRESQL.getUrlFormatString(),
            container.getHost(),
            container.getFirstMappedPort(),
            config.get(JdbcUtils.DATABASE_KEY).asText()),
        SQLDialect.POSTGRES)) {
      database = new Database(dslContext);

      database.query(ctx -> {
        ctx.fetch("CREATE TABLE id_and_name(id INTEGER, name VARCHAR(200));");
        ctx.fetch("INSERT INTO id_and_name (id, name) VALUES (1,'picard'),  (2, 'crusher'), (3, 'vash');");
        ctx.fetch("CREATE TABLE starships(id INTEGER, name VARCHAR(200));");
        ctx.fetch("INSERT INTO starships (id, name) VALUES (1,'enterprise-d'),  (2, 'defiant'), (3, 'yamato');");
        ctx.fetch("CREATE MATERIALIZED VIEW testview AS select * from id_and_name where id = '2';");
        return null;
      });
      configCatalog = getXminCatalog();
    }
  }

  private JsonNode getConfig(final String username, final String password, final List<String> schemas) {
    final JsonNode replicationMethod = Jsons.jsonNode(ImmutableMap.builder()
        .put("method", "Standard")
        .build());
    return Jsons.jsonNode(ImmutableMap.builder()
        .put(JdbcUtils.HOST_KEY, HostPortResolver.resolveHost(container))
        .put(JdbcUtils.PORT_KEY, HostPortResolver.resolvePort(container))
        .put(JdbcUtils.DATABASE_KEY, container.getDatabaseName())
        .put(JdbcUtils.SCHEMAS_KEY, Jsons.jsonNode(schemas))
        .put(JdbcUtils.USERNAME_KEY, username)
        .put(JdbcUtils.PASSWORD_KEY, password)
        .put(JdbcUtils.SSL_KEY, false)
        .put("replication_method", replicationMethod)
        .build());
  }

  private JsonNode getXminConfig(final String username, final String password, final List<String> schemas) {
    final JsonNode replicationMethod = Jsons.jsonNode(ImmutableMap.builder()
        .put("method", "Xmin")
        .build());
    return Jsons.jsonNode(ImmutableMap.builder()
        .put(JdbcUtils.HOST_KEY, HostPortResolver.resolveHost(container))
        .put(JdbcUtils.PORT_KEY, HostPortResolver.resolvePort(container))
        .put(JdbcUtils.DATABASE_KEY, container.getDatabaseName())
        .put(JdbcUtils.SCHEMAS_KEY, Jsons.jsonNode(schemas))
        .put(JdbcUtils.USERNAME_KEY, username)
        .put(JdbcUtils.PASSWORD_KEY, password)
        .put(JdbcUtils.SSL_KEY, false)
        .put("replication_method", replicationMethod)
        .build());
  }

  @Override
  protected void tearDown(final TestDestinationEnv testEnv) throws Exception {
    container.close();
  }

  @Override
  protected ConfiguredAirbyteCatalog getConfiguredCatalog() throws Exception {
    return configCatalog;
  }

  @Override
  protected JsonNode getState() throws Exception {
    return Jsons.jsonNode(new HashMap<>());
  }

  @Override
  protected boolean supportsPerStream() {
    return true;
  }

  private ConfiguredAirbyteCatalog getCommonConfigCatalog() {
    return new ConfiguredAirbyteCatalog().withStreams(Lists.newArrayList(
        new ConfiguredAirbyteStream()
            .withSyncMode(SyncMode.INCREMENTAL)
            .withCursorField(Lists.newArrayList("id"))
            .withDestinationSyncMode(DestinationSyncMode.APPEND)
            .withStream(CatalogHelpers.createAirbyteStream(
                    STREAM_NAME, SCHEMA_NAME,
                    Field.of("id", JsonSchemaType.NUMBER),
                    Field.of("name", JsonSchemaType.STRING))
                .withSupportedSyncModes(Lists.newArrayList(SyncMode.INCREMENTAL))
                .withSourceDefinedPrimaryKey(List.of(List.of("id")))),
        new ConfiguredAirbyteStream()
            .withSyncMode(SyncMode.INCREMENTAL)
            .withCursorField(Lists.newArrayList("id"))
            .withDestinationSyncMode(DestinationSyncMode.APPEND)
            .withStream(CatalogHelpers.createAirbyteStream(
                    STREAM_NAME2, SCHEMA_NAME,
                    Field.of("id", JsonSchemaType.NUMBER),
                    Field.of("name", JsonSchemaType.STRING))
                .withSupportedSyncModes(Lists.newArrayList(SyncMode.INCREMENTAL))
                .withSourceDefinedPrimaryKey(List.of(List.of("id")))),
        new ConfiguredAirbyteStream()
            .withSyncMode(SyncMode.INCREMENTAL)
            .withCursorField(Lists.newArrayList("id"))
            .withDestinationSyncMode(DestinationSyncMode.APPEND)
            .withStream(CatalogHelpers.createAirbyteStream(
                    STREAM_NAME_MATERIALIZED_VIEW, SCHEMA_NAME,
                    Field.of("id", JsonSchemaType.NUMBER),
                    Field.of("name", JsonSchemaType.STRING))
                .withSupportedSyncModes(Lists.newArrayList(SyncMode.INCREMENTAL))
                .withSourceDefinedPrimaryKey(List.of(List.of("id"))))));
  }

  private ConfiguredAirbyteCatalog getXminCatalog() {
    return new ConfiguredAirbyteCatalog().withStreams(Lists.newArrayList(
        new ConfiguredAirbyteStream()
            .withSyncMode(SyncMode.INCREMENTAL)
            .withDestinationSyncMode(DestinationSyncMode.APPEND)
            .withStream(CatalogHelpers.createAirbyteStream(
                    STREAM_NAME, SCHEMA_NAME,
                    Field.of("id", JsonSchemaType.NUMBER),
                    Field.of("name", JsonSchemaType.STRING))
                .withSupportedSyncModes(Lists.newArrayList(SyncMode.INCREMENTAL))
                .withSourceDefinedCursor(true)
                .withSourceDefinedPrimaryKey(List.of(List.of("id")))),
        new ConfiguredAirbyteStream()
            .withSyncMode(SyncMode.INCREMENTAL)
            .withDestinationSyncMode(DestinationSyncMode.APPEND)
            .withStream(CatalogHelpers.createAirbyteStream(
                    STREAM_NAME2, SCHEMA_NAME,
                    Field.of("id", JsonSchemaType.NUMBER),
                    Field.of("name", JsonSchemaType.STRING))
                .withSupportedSyncModes(Lists.newArrayList(SyncMode.INCREMENTAL))
                .withSourceDefinedCursor(true)
                .withSourceDefinedPrimaryKey(List.of(List.of("id")))),
        new ConfiguredAirbyteStream()
            .withSyncMode(SyncMode.INCREMENTAL)
            .withDestinationSyncMode(DestinationSyncMode.APPEND)
            .withStream(CatalogHelpers.createAirbyteStream(
                    STREAM_NAME_MATERIALIZED_VIEW, SCHEMA_NAME,
                    Field.of("id", JsonSchemaType.NUMBER),
                    Field.of("name", JsonSchemaType.STRING))
                .withSupportedSyncModes(Lists.newArrayList(SyncMode.INCREMENTAL))
                .withSourceDefinedCursor(true)
                .withSourceDefinedPrimaryKey(List.of(List.of("id"))))));
  }
}