package io.vertx.ext.web.validation.impl;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.json.schema.SchemaParser;
import io.vertx.ext.json.schema.SchemaParserOptions;
import io.vertx.ext.json.schema.SchemaRouter;
import io.vertx.ext.json.schema.SchemaRouterOptions;
import io.vertx.ext.json.schema.draft7.Draft7SchemaParser;
import io.vertx.ext.json.schema.generic.dsl.ObjectSchemaBuilder;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.validation.BodyProcessor;
import io.vertx.ext.web.validation.BodyProcessorException;
import io.vertx.ext.web.validation.MalformedValueException;
import io.vertx.ext.web.validation.dsl.BodyProcessorFactory;
import io.vertx.ext.web.validation.testutils.TestSchemas;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
@ExtendWith(MockitoExtension.class)
class FormBodyProcessorImplTest {

  SchemaRouter router;
  SchemaParser parser;

  @Mock RoutingContext mockedContext;
  @Mock HttpServerRequest mockedServerRequest;

  @BeforeEach
  public void setUp(Vertx vertx) {
    router = SchemaRouter.create(vertx, new SchemaRouterOptions());
    parser = Draft7SchemaParser.create(new SchemaParserOptions(), router);
  }

  @Test
  public void testFormBodyProcessor(VertxTestContext testContext) {
    ObjectSchemaBuilder schemaBuilder = TestSchemas.SAMPLE_OBJECT_SCHEMA_BUILDER;

    MultiMap map = MultiMap.caseInsensitiveMultiMap();
    map.add("someNumbers", "1.1");
    map.add("someNumbers", "2.2");
    map.add("oneNumber", "3.3");
    map.add("someIntegers", "1");
    map.add("someIntegers", "2");
    map.add("oneInteger", "3");
    map.add("aBoolean", "true");

    when(mockedServerRequest.formAttributes()).thenReturn(map);
    when(mockedContext.request()).thenReturn(mockedServerRequest);

    BodyProcessor processor = BodyProcessorFactory.formUrlEncoded(schemaBuilder).create(parser);

    assertThat(processor.canProcess("application/x-www-form-urlencoded")).isTrue();

    processor.process(mockedContext).setHandler(testContext.succeeding(rp -> {
      testContext.verify(() -> {
        assertThat(rp.isJsonObject())
          .isTrue();
        assertThat(rp.getJsonObject())
          .isEqualTo(
            new JsonObject()
              .put("someNumbers", new JsonArray().add(1.1d).add(2.2d))
              .put("oneNumber", 3.3d)
              .put("someIntegers", new JsonArray().add(1L).add(2L))
              .put("oneInteger", 3L)
              .put("aBoolean", true)
          );
      });
      testContext.completeNow();
    }));

  }

  @Test
  public void testFormBodyProcessorParsingFailure(VertxTestContext testContext) {
    ObjectSchemaBuilder schemaBuilder = TestSchemas.SAMPLE_OBJECT_SCHEMA_BUILDER;

    MultiMap map = MultiMap.caseInsensitiveMultiMap();
    map.add("someNumbers", "1.1");
    map.add("someNumbers", "2.2");
    map.add("oneNumber", "3.3");
    map.add("someIntegers", "1");
    map.add("someIntegers", "hello");
    map.add("oneInteger", "3");
    map.add("aBoolean", "true");

    when(mockedServerRequest.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("application/x-www-form-urlencoded");
    when(mockedServerRequest.formAttributes()).thenReturn(map);
    when(mockedContext.request()).thenReturn(mockedServerRequest);

    BodyProcessor processor = BodyProcessorFactory.formUrlEncoded(schemaBuilder).create(parser);

    assertThat(processor.canProcess("application/x-www-form-urlencoded")).isTrue();

    processor.process(mockedContext).setHandler(testContext.failing(err -> {
      testContext.verify(() -> {
        assertThat(err)
          .isInstanceOf(BodyProcessorException.class)
          .hasFieldOrPropertyWithValue("actualContentType", "application/x-www-form-urlencoded")
          .hasCauseInstanceOf(MalformedValueException.class);
      });
      testContext.completeNow();
    }));

  }

}