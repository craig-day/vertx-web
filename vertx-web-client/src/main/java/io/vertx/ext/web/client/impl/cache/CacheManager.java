/*
 * Copyright 2021 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.ext.web.client.impl.cache;

import io.netty.handler.codec.DateFormatter;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.CachingWebClientOptions;
import io.vertx.ext.web.client.spi.CacheStore;
import io.vertx.ext.web.client.impl.HttpRequestImpl;
import java.util.Date;

/**
 * HTTP cache manager to process requests and responses and either reply from, or store in a cache.
 *
 * @author <a href="mailto:craigday3@gmail.com">Craig Day</a>
 */
public class CacheManager {

  private final CacheStore cacheStore;
  private final CachingWebClientOptions options;

  public CacheManager(CacheStore cacheStore, CachingWebClientOptions options) {
    this.cacheStore = cacheStore;
    this.options = options;
  }

  public Future<HttpResponse<Buffer>> processRequest(HttpRequest<Buffer> request) {
    if (!options.isCachingEnabled()) {
      return request.send();
    }

    CacheKey key = new CacheKey(request, options);

    return cacheStore
      .get(key)
      .compose(resp -> respondFromCache((HttpRequestImpl<Buffer>) request, resp));
  }

  public Future<HttpResponse<Buffer>> processResponse(HttpRequest<Buffer> request, HttpResponse<Buffer> response) {
    if (!options.isCachingEnabled()) {
      return Future.succeededFuture(response);
    }

    return processResponse(request, response, false);
  }

  private Future<HttpResponse<Buffer>> processResponse(HttpRequest<Buffer> request, HttpResponse<Buffer> response, boolean wasStale) {
    if (wasStale && response.statusCode() == 304) {
      // The cache returned a stale result, but server has confirmed still good. Update cache
      return cacheResponse(request, response).map(response);
    } else if (options.getCachedStatusCodes().contains(response.statusCode())) {
      // Request was successful, attempt to cache response
      return cacheResponse(request, response).map(response);
    } else {
      // Response is not cacheable, do nothing
      return Future.succeededFuture(response);
    }
  }

  private Future<HttpResponse<Buffer>> respondFromCache(HttpRequestImpl<Buffer> request, CachedHttpResponse response) {
    if (response == null) {
      return Future.failedFuture("http cache miss");
    }

    CacheControl cacheControl = response.cacheControl();

    if (response.isFresh()) {
      if (cacheControl.isVarying() && options.isVaryCachingEnabled()) {
        return handleVaryingCache(request, response);
      } else {
        HttpResponse<Buffer> result = response.rehydrate();
        result.headers().set(HttpHeaders.AGE, DateFormatter.format(new Date(response.age())));
        return Future.succeededFuture(result);
      }
    } else {
      return handleStaleCacheResult(request, cacheControl);
    }
  }

  private Future<HttpResponse<Buffer>> handleStaleCacheResult(HttpRequestImpl<Buffer> request, CacheControl cacheControl) {
    // We could also add support for stale-while-revalidate and stale-if-error here if desired
    request.headers().set(HttpHeaders.IF_NONE_MATCH, cacheControl.etag());
    // TODO: should we delete the stale response from the cache?
    return request
      .send()
      .compose(updatedResponse -> processResponse(request, updatedResponse, true));
  }

  private Future<HttpResponse<Buffer>> handleVaryingCache(HttpRequestImpl<?> request, CachedHttpResponse response) {
    if (response.vary().matchesRequest(request)) {
      return Future.succeededFuture(response.rehydrate());
    } else {
      return Future.failedFuture("matching variation not found");
    }
  }

  private Future<Void> cacheResponse(HttpRequest<?> request, HttpResponse<Buffer> response) {
    CacheControl cacheControl = CacheControl.parse(response.headers());

    if (!cacheControl.isCacheable()) {
      return Future.succeededFuture();
    }

    if (cacheControl.isPrivate() && !options.isPrivateCachingEnabled()) {
      return Future.succeededFuture();
    }

    if (cacheControl.isVarying() && !options.isVaryCachingEnabled()) {
      return Future.succeededFuture();
    }

    CacheKey key = new CacheKey(request, options);
    CachedHttpResponse cachedResponse = CachedHttpResponse.wrap(response, cacheControl);

    return cacheStore.set(key, cachedResponse).mapEmpty();
  }
}
