package io.vertx.ext.web.client.impl.cache;

import io.vertx.codegen.annotations.VertxGen;

@VertxGen
public interface CacheControlDirectives {

  String PUBLIC = "public";
  String PRIVATE = "private";
  String NO_STORE = "no-store";
  String NO_CACHE = "no-cache";
  String SHARED_MAX_AGE = "s-maxage";
  String MAX_AGE = "max-age";
  String STALE_IF_ERROR = "stale-if-error";
  String STALE_WHILE_REVALIDATE = "stale-while-revalidate";
}
