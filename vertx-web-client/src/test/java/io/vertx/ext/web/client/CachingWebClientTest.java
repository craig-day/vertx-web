package io.vertx.ext.web.client;

import io.netty.handler.codec.DateFormatter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.client.impl.cache.CacheKey;
import io.vertx.ext.web.client.impl.cache.CachedHttpResponse;
import io.vertx.ext.web.client.spi.CacheStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author <a href="mailto:craigday3@gmail.com">Craig Day</a>
 */
@RunWith(VertxUnitRunner.class)
public class CachingWebClientTest {

  private static final int PORT = 8080;

  private TestCacheStore store;
  private TestCacheStore privateStore;
  private WebClient defaultClient;
  private WebClient privateClient;
  private WebClient baseWebClient;
  private Vertx vertx;
  private HttpServer server;

  private WebClient buildBaseWebClient() {
    HttpClientOptions opts = new HttpClientOptions().setDefaultPort(PORT).setDefaultHost("localhost");
    return WebClient.wrap(vertx.createHttpClient(opts));
  }

  private HttpServer buildHttpServer() {
    HttpServerOptions opts = new HttpServerOptions().setPort(PORT).setHost("0.0.0.0");
    return vertx.createHttpServer(opts);
  }

  @Before
  public void setUp() {
    vertx = Vertx.vertx();
    baseWebClient = buildBaseWebClient();
    store = new TestCacheStore();
    privateStore = new TestCacheStore();
    defaultClient = CachingWebClient.create(baseWebClient, store, new CachingWebClientOptions(true, false, false));
    privateClient = CachingWebClient.create(baseWebClient, privateStore, new CachingWebClientOptions(true, true, false));
    server = buildHttpServer();
  }

  @After
  public void tearDown(TestContext context) {
    store.flush(context.asyncAssertSuccess());
    privateStore.flush(context.asyncAssertSuccess());
    vertx.close(context.asyncAssertSuccess());
  }

  private void startMockServer(TestContext context, Consumer<HttpServerRequest> reqHandler) {
    Async async = context.async();
    server.requestHandler(req -> {
      try {
        reqHandler.accept(req);
      } finally {
        if (!req.response().ended())
          req.response().end(UUID.randomUUID().toString());
      }
    });
    server.listen(context.asyncAssertSuccess(s -> async.complete()));
    async.awaitSuccess(15000);
  }

  private void startMockServer(TestContext context, String cacheControl) {
    startMockServer(context, req -> {
      req.response().headers().set("Cache-Control", cacheControl);
    });
  }

  private void assertCacheUse(TestContext context, HttpMethod method, WebClient client, boolean shouldCacheBeUsed) {
    Async request1 = context.async();
    Async request2 = context.async();
    List<HttpResponse<Buffer>> responses = new ArrayList<>(2);

    client.request(method, "localhost", "/").send(context.asyncAssertSuccess(resp -> {
      responses.add(resp);
      request1.complete();
    }));

    // Wait for request 1 to finish first to make sure the cache stored a value if necessary
    request1.await();

    client.request(method, "localhost", "/").send(context.asyncAssertSuccess(resp -> {
      responses.add(resp);
      request2.complete();
    }));

    request2.await();

    context.assertTrue(responses.size() == 2);

    HttpResponse<Buffer> resp1 = responses.get(0);
    HttpResponse<Buffer> resp2 = responses.get(1);

    if (shouldCacheBeUsed) {
      context.assertEquals(resp1.bodyAsString(), resp2.bodyAsString());
      context.assertNotNull(resp1.headers().get(HttpHeaders.AGE));
      context.assertNotNull(resp2.headers().get(HttpHeaders.AGE));
    } else {
      context.assertNotEquals(resp1.bodyAsString(), resp2.bodyAsString());
      context.assertNull(resp1.headers().get(HttpHeaders.AGE));
      context.assertNull(resp2.headers().get(HttpHeaders.AGE));
    }
  }

  private void assertCached(TestContext context, WebClient client) {
    assertCacheUse(context, HttpMethod.GET, client, true);
  }

  private void assertCached(TestContext context) {
    assertCached(context, defaultClient);
  }

  private void assertNotCached(TestContext context, WebClient client) {
    assertCacheUse(context, HttpMethod.GET, client, false);
  }

  private void assertNotCached(TestContext context) {
    assertNotCached(context, defaultClient);
  }

  // Non-GET methods that we shouldn't cache

  @Test
  public void testPOSTNotCached(TestContext context) {
    startMockServer(context, "public, max-age=600");
    assertCacheUse(context, HttpMethod.POST, defaultClient, false);
  }

  @Test
  public void testPOSTNotPrivatelyCached(TestContext context) {
    startMockServer(context, "private, max-age=600");
    assertCacheUse(context, HttpMethod.POST, privateClient, false);
  }

  @Test
  public void testPUTNotCached(TestContext context) {
    startMockServer(context, "public, max-age=600");
    assertCacheUse(context, HttpMethod.PUT, defaultClient, false);
  }

  @Test
  public void testPUTNotPrivatelyCached(TestContext context) {
    startMockServer(context, "private, max-age=600");
    assertCacheUse(context, HttpMethod.PUT, privateClient, false);
  }

  @Test
  public void testPATCHNotCached(TestContext context) {
    startMockServer(context, "public, max-age=600");
    assertCacheUse(context, HttpMethod.PATCH, defaultClient, false);
  }

  @Test
  public void testPATCHNotPrivatelyCached(TestContext context) {
    startMockServer(context, "private, max-age=600");
    assertCacheUse(context, HttpMethod.PATCH, privateClient, false);
  }

  @Test
  public void testDELETENotCached(TestContext context) {
    startMockServer(context, "public, max-age=600");
    assertCacheUse(context, HttpMethod.DELETE, defaultClient, false);
  }

  @Test
  public void testDELETENotPrivatelyCached(TestContext context) {
    startMockServer(context, "private, max-age=600");
    assertCacheUse(context, HttpMethod.DELETE, privateClient, false);
  }

  // Cache-Control: no-store || no-cache

  @Test
  public void testNoStore(TestContext context) {
    startMockServer(context, "no-store");
    assertNotCached(context);
  }

  @Test
  public void testNoCache(TestContext context) {
    startMockServer(context, "no-cache");
    assertNotCached(context);
  }

  // Cache-Control: public

  @Test
  public void testPublicWithMaxAge(TestContext context) {
    startMockServer(context, "public, max-age=600");
    assertCached(context);
  }

  @Test
  public void testPublicWithMaxAgeMultiHeader(TestContext context) {
    startMockServer(context, req -> {
      req.response().headers().add("Cache-Control", "public");
      req.response().headers().add("Cache-Control", "max-age=600");
    });

    assertCached(context);
  }

  @Test
  public void testPublicWithoutMaxAge(TestContext context) {
    startMockServer(context, "public");
    assertCached(context);
  }

  @Test
  public void testPublicMaxAgeZero(TestContext context) {
    startMockServer(context, "public,max-age=0");
    assertNotCached(context);
  }

  @Test
  public void testPublicSharedMaxAge(TestContext context) {
    startMockServer(context, "public, s-maxage=600");
    assertCached(context);
  }

  @Test
  public void testPublicSharedMaxAgeZero(TestContext context) {
    startMockServer(context, "public, s-maxage=0");
    assertNotCached(context);
  }

  @Test
  public void testPublicWithExpiresNow(TestContext context) {
    startMockServer(context, req -> {
      req.response().headers().set("Cache-Control", "public");
      req.response().headers().set("Expires", DateFormatter.format(new Date()));
    });

    assertNotCached(context);
  }

  @Test
  public void testPublicWithExpiresPast(TestContext context) {
    String expires = DateFormatter.format(new Date(
      System.currentTimeMillis() - Duration.ofMinutes(5).toMillis()
    ));
    startMockServer(context, req -> {
      req.response().headers().set("Cache-Control", "public");
      req.response().headers().set("Expires", expires);
    });

    assertNotCached(context);
  }

  @Test
  public void testPublicWithExpiresFuture(TestContext context) {
    String expires = DateFormatter.format(new Date(
      System.currentTimeMillis() + Duration.ofMinutes(5).toMillis()
    ));
    startMockServer(context, req -> {
      req.response().headers().set("Cache-Control", "public");
      req.response().headers().set("Expires", expires);
    });

    assertCached(context);
  }

  @Test
  public void testPublicWithMaxAgeFutureAndExpiresPast(TestContext context) {
    String expires = DateFormatter.format(new Date(
      System.currentTimeMillis() - Duration.ofMinutes(5).toMillis()
    ));
    startMockServer(context, req -> {
      req.response().headers().set("Cache-Control", "public, max-age=300");
      req.response().headers().set("Expires", expires);
    });

    assertCached(context);
  }

  @Test
  public void testPublicWithMaxAgeFutureAndExpiresFuture(TestContext context) {
    String expires = DateFormatter.format(new Date(
      System.currentTimeMillis() + Duration.ofMinutes(5).toMillis()
    ));

    Async req1 = context.async();
    Async req2 = context.async();
    Async req3 = context.async();
    Async waiter = context.async();
    List<HttpResponse<Buffer>> responses = new ArrayList<>(3);

    startMockServer(context, req -> {
      String maxAge = req1.isCompleted() ? "0" : "1";
      req.response().headers().set("Cache-Control", "public, max-age=" + maxAge);
      req.response().headers().set("Expires", expires);
    });

    defaultClient.get("localhost", "/").send(context.asyncAssertSuccess(resp -> {
      responses.add(resp);
      req1.complete();
    }));
    req1.await();

    defaultClient.get("localhost", "/").send(context.asyncAssertSuccess(resp -> {
      responses.add(resp);
      req2.complete();
    }));
    req2.await();

    // HTTP cache only has 1 second resolution, so this must be 1+ seconds past than the max-age
    vertx.setTimer(2000, l -> waiter.complete());
    waiter.await();

    defaultClient.get("localhost", "/").send(context.asyncAssertSuccess(resp -> {
      responses.add(resp);
      req3.complete();
    }));
    req3.await();

    context.assertEquals(responses.size(), 3);
    context.assertEquals(responses.get(0).bodyAsString(), responses.get(1).bodyAsString());
    context.assertNotEquals(responses.get(1).bodyAsString(), responses.get(2).bodyAsString());
  }

  @Test
  public void testPublicWithMaxAgeZeroAndExpiresFuture(TestContext context) {
    String expires = DateFormatter.format(new Date(
      System.currentTimeMillis() + Duration.ofMinutes(5).toMillis()
    ));
    startMockServer(context, req -> {
      req.response().headers().set("Cache-Control", "public, max-age=0");
      req.response().headers().set("Expires", expires);
    });

    assertNotCached(context);
  }

  @Test
  public void testPublicWithMaxAgeZeroAndExpiresZero(TestContext context) {
    String expires = DateFormatter.format(new Date());
    startMockServer(context, req -> {
      req.response().headers().set("Cache-Control", "public, max-age=0");
      req.response().headers().set("Expires", expires);
    });

    assertNotCached(context);
  }

  @Test
  public void testPublicAndPrivate(TestContext context) {
    // This is a silly case because it is invalid, but it validates that we err on the side of not
    // caching responses.
    startMockServer(context, "public, private, max-age=300");
    assertNotCached(context);
  }

  @Test
  public void testUpdateStaleResponse(TestContext context) {
    Async req1 = context.async();
    Async req2 = context.async();
    Async req3 = context.async();
    Async waiter = context.async();
    List<HttpResponse<Buffer>> responses = new ArrayList<>(3);

    startMockServer(context, "public, max-age=1");

    defaultClient.get("localhost", "/").send(context.asyncAssertSuccess(resp -> {
      responses.add(resp);
      req1.complete();
    }));
    req1.await();

    vertx.setTimer(2000, l -> waiter.complete());
    waiter.await();

    defaultClient.get("localhost", "/").send(context.asyncAssertSuccess(resp -> {
      responses.add(resp);
      req2.complete();
    }));
    req2.await();

    defaultClient.get("localhost", "/").send(context.asyncAssertSuccess(resp -> {
      responses.add(resp);
      req3.complete();
    }));
    req3.await();

    context.assertEquals(responses.size(), 3);
    context.assertNotEquals(responses.get(0).bodyAsString(), responses.get(1).bodyAsString());
    context.assertEquals(responses.get(1).bodyAsString(), responses.get(2).bodyAsString());
  }

  @Test
  public void testWithMatchingQueryParams(TestContext context) {
    startMockServer(context, "public, max-age=300");

    Async req1 = context.async();
    Async req2 = context.async();
    AtomicReference<String> body1 = new AtomicReference<>();
    AtomicReference<String> body2 = new AtomicReference<>();

    defaultClient
      .get("localhost", "/")
      .setQueryParam("q", "search")
      .send(context.asyncAssertSuccess(resp -> {
        body1.set(resp.bodyAsString());
        req1.complete();
      }));
    req1.await();

    defaultClient
      .get("localhost", "/")
      .setQueryParam("q", "search")
      .send(context.asyncAssertSuccess(resp -> {
        body2.set(resp.bodyAsString());
        req2.complete();
      }));
    req2.await();

    context.assertNotNull(body1.get());
    context.assertNotNull(body2.get());
    context.assertEquals(body1.get(), body2.get());
  }

  @Test
  public void testWithDifferentQueryParams(TestContext context) {
    startMockServer(context, "public, max-age=300");

    Async req1 = context.async();
    Async req2 = context.async();
    AtomicReference<String> body1 = new AtomicReference<>();
    AtomicReference<String> body2 = new AtomicReference<>();

    defaultClient
      .get("localhost", "/")
      .setQueryParam("q", "search")
      .send(context.asyncAssertSuccess(resp -> {
        body1.set(resp.bodyAsString());
        req1.complete();
      }));
    req1.await();

    defaultClient
      .get("localhost", "/")
      .setQueryParam("q", "other")
      .send(context.asyncAssertSuccess(resp -> {
        body2.set(resp.bodyAsString());
        req2.complete();
      }));
    req2.await();

    context.assertNotNull(body1.get());
    context.assertNotNull(body2.get());
    context.assertNotEquals(body1.get(), body2.get());
  }

  // Cache-Control: private with client NOT enabled private caching

  @Test
  public void testPrivate(TestContext context) {
    startMockServer(context, "private");
    assertNotCached(context);
  }

  @Test
  public void testPrivateMaxAge(TestContext context) {
    startMockServer(context, "private, max-age=300");
    assertNotCached(context);
  }

  @Test
  public void testPrivateMaxAgeZero(TestContext context) {
    startMockServer(context, "private, max-age=0");
    assertNotCached(context);
  }

  @Test
  public void testPrivateExpires(TestContext context) {
    String expires = DateFormatter.format(new Date(
      System.currentTimeMillis() + Duration.ofMinutes(5).toMillis()
    ));
    startMockServer(context, req -> {
      req.response().headers().add("Cache-Control", "private");
      req.response().headers().add("Expires", expires);
    });

    assertNotCached(context);
  }

  // Cache-Control: private with client enabled private caching

  @Test
  public void testPrivateEnabled(TestContext context) {
    startMockServer(context, "private");
    assertCached(context, privateClient);
  }

  @Test
  public void testPrivateEnabledMaxAge(TestContext context) {
    startMockServer(context, "private, max-age=300");
    assertCached(context, privateClient);
  }

  @Test
  public void testPrivateEnabledMaxAgeZero(TestContext context) {
    startMockServer(context, "private, max-age=0");
    assertNotCached(context, privateClient);
  }

  @Test
  public void testPrivateSharedMaxAgeAndMaxAgeZero(TestContext context) {
    startMockServer(context, "private, s-maxage=300, max-age=0");
    assertNotCached(context, privateClient);
  }

  @Test
  public void testPrivateSharedMaxAgeAndMaxAge(TestContext context) {
    startMockServer(context, "private, s-maxage=300, max-age=1");

    Async req1 = context.async();
    Async req2 = context.async();
    Async req3 = context.async();
    Async waiter = context.async();
    List<HttpResponse<Buffer>> responses = new ArrayList<>(3);

    privateClient.get("localhost", "/").send(context.asyncAssertSuccess(resp -> {
      responses.add(resp);
      req1.complete();
    }));
    req1.await();

    privateClient.get("localhost", "/").send(context.asyncAssertSuccess(resp -> {
      responses.add(resp);
      req2.complete();
    }));
    req2.await();

    // Wait for the max-age time to pass, but not long enough for s-maxage
    // HTTP cache only has 1 second resolution, so this must be 1+ seconds past than the max-age
    vertx.setTimer(2000, l -> waiter.complete());
    waiter.await();

    privateClient.get("localhost", "/").send(context.asyncAssertSuccess(resp -> {
      responses.add(resp);
      req3.complete();
    }));
    req3.await();

    context.assertEquals(responses.size(), 3);
    context.assertEquals(responses.get(0).bodyAsString(), responses.get(1).bodyAsString());
    context.assertNotEquals(responses.get(1).bodyAsString(), responses.get(2).bodyAsString());
  }

  // Cache-Control: public; Vary: User-Agent

  @Test
  public void testPublicVaryMaxAgeZero(TestContext context) {
    WebClient client = CachingWebClient.create(defaultClient, new TestCacheStore(),
      new CachingWebClientOptions(true, false, true));

    startMockServer(context, req -> {
      req.response().headers().add("Cache-Control", "public, max-age=0");
      req.response().headers().add("Vary", "User-Agent");
    });

    assertNotCached(context, client);
  }

  @Test
  public void testVaryUserAgentTwoDesktops(TestContext context) {
    WebClient client = CachingWebClient.create(baseWebClient, new TestCacheStore(),
      new CachingWebClientOptions(true, false, true));

    startMockServer(context, req -> {
      req.response().headers().add("Cache-Control", "public, max-age=300");
      req.response().headers().add("Vary", "User-Agent");
    });

    Async req1 = context.async();
    Async req2 = context.async();
    AtomicReference<String> body1 = new AtomicReference<>();
    AtomicReference<String> body2 = new AtomicReference<>();

    // Chrome Desktop
    client
      .get("localhost", "/")
      .putHeader("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/51.0.2704.103 Safari/537.36")
      .send(context.asyncAssertSuccess(resp -> {
        body1.set(resp.bodyAsString());
        req1.complete();
      }));
    req1.await();

    // Firefox Desktop
    client
      .get("localhost", "/")
      .putHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X x.y; rv:42.0) Gecko/20100101 Firefox/42.0")
      .send(context.asyncAssertSuccess(resp -> {
        body2.set(resp.bodyAsString());
        req2.complete();
      }));
    req2.await();

    // Desktop user agents are normalized so two desktop clients should hit the same cache
    context.assertNotNull(body1.get());
    context.assertNotNull(body2.get());
    context.assertEquals(body1.get(), body2.get());
  }

  @Test
  public void testVaryUserAgentDesktopVsMobile(TestContext context) {
    WebClient client = CachingWebClient.create(baseWebClient, new TestCacheStore(),
      new CachingWebClientOptions(true, false, true));

    startMockServer(context, req -> {
      req.response().headers().add("Cache-Control", "public, max-age=300");
      req.response().headers().add("Vary", "User-Agent");
    });

    Async req1 = context.async();
    Async req2 = context.async();
    AtomicReference<String> body1 = new AtomicReference<>();
    AtomicReference<String> body2 = new AtomicReference<>();

    // Chrome Desktop
    client
      .get("localhost", "/")
      .putHeader("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/51.0.2704.103 Safari/537.36")
      .send(context.asyncAssertSuccess(resp -> {
        body1.set(resp.bodyAsString());
        req1.complete();
      }));
    req1.await();

    // iPhone Mobile
    client
      .get("localhost", "/")
      .putHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.1.1 Mobile/15E148 Safari/604.1")
      .send(context.asyncAssertSuccess(resp -> {
        body2.set(resp.bodyAsString());
        req2.complete();
      }));
    req2.await();

    // Desktop and Mobile may receive different content and should not share a cache
    context.assertNotNull(body1.get());
    context.assertNotNull(body2.get());
    context.assertNotEquals(body1.get(), body2.get());
  }

  // Cache-Control: public; Vary: Content-Encoding

  @Test
  public void testVaryEncodingOverlap(TestContext context) {
    WebClient client = CachingWebClient.create(baseWebClient, new TestCacheStore(),
      new CachingWebClientOptions(true, false, true));

    startMockServer(context, req -> {
      req.response().headers().add("Cache-Control", "public, max-age=300");
      req.response().headers().add("Content-Encoding", "gzip");
      req.response().headers().add("Vary", "Accept-Encoding");
    });

    Async req1 = context.async();
    Async req2 = context.async();
    AtomicReference<String> body1 = new AtomicReference<>();
    AtomicReference<String> body2 = new AtomicReference<>();

    client
      .get("localhost", "/")
      .putHeader("Accept-Encoding", "gzip,deflate,sdch")
      .send(context.asyncAssertSuccess(resp -> {
        body1.set(resp.bodyAsString());
        req1.complete();
      }));
    req1.await();

    client
      .get("localhost", "/")
      .putHeader("Accept-Encoding", "gzip,deflate")
      .send(context.asyncAssertSuccess(resp -> {
        body2.set(resp.bodyAsString());
        req2.complete();
      }));
    req2.await();

    // Both accept gzip, so cache should be used
    context.assertNotNull(body1.get());
    context.assertNotNull(body2.get());
    context.assertEquals(body1.get(), body2.get());
  }

  @Test
  public void testVaryEncodingDifferent(TestContext context) {
    WebClient client = CachingWebClient.create(baseWebClient, new TestCacheStore(),
      new CachingWebClientOptions(true, false, true));

    startMockServer(context, req -> {
      req.response().headers().add("Cache-Control", "public, max-age=300");
      req.response().headers().add("Content-Encoding", "gzip");
      req.response().headers().add("Vary", "Accept-Encoding");
    });

    Async req1 = context.async();
    Async req2 = context.async();
    AtomicReference<String> body1 = new AtomicReference<>();
    AtomicReference<String> body2 = new AtomicReference<>();

    client
      .get("localhost", "/")
      .putHeader("Accept-Encoding", "gzip,deflate")
      .send(context.asyncAssertSuccess(resp -> {
        body1.set(resp.bodyAsString());
        req1.complete();
      }));
    req1.await();

    client
      .get("localhost", "/")
      .putHeader("Accept-Encoding", "br")
      .send(context.asyncAssertSuccess(resp -> {
        body2.set(resp.bodyAsString());
        req2.complete();
      }));
    req2.await();

    context.assertNotNull(body1.get());
    context.assertNotNull(body2.get());
    context.assertNotEquals(body1.get(), body2.get());
  }

  static class TestCacheStore implements CacheStore {
    public final Map<String, CachedHttpResponse> db = new ConcurrentHashMap<>();

    @Override
    public Future<CachedHttpResponse> get(CacheKey key) {
      return Future.succeededFuture(db.get(key.toString()));
    }

    @Override
    public Future<CachedHttpResponse> set(CacheKey key, CachedHttpResponse response) {
      db.put(key.toString(), response);
      return Future.succeededFuture(response);
    }

    @Override
    public Future<Void> delete(CacheKey key) {
      db.remove(key.toString());
      return Future.succeededFuture();
    }

    @Override
    public Future<Void> flush() {
      db.clear();
      return Future.succeededFuture();
    }
  }
}
