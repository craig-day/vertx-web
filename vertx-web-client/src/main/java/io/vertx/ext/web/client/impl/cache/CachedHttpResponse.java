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

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.spi.CacheStore;
import io.vertx.ext.web.client.impl.HttpResponseImpl;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

/**
 * A serializable object to be stored by a {@link CacheStore}.
 *
 * @author <a href="mailto:craigday3@gmail.com">Craig Day</a>
 */
public class CachedHttpResponse implements Serializable {

  private static final long serialVersionUID = 1L;

  private final String version;
  private final int statusCode;
  private final String statusMessage;
  private final Buffer body;
  private final MultiMap requestHeaders;
  private final MultiMap responseHeaders;
  private final Instant timestamp;

  transient private CacheControl cacheControl;
  transient private Vary vary;

  static CachedHttpResponse wrap(HttpRequest<?> request, HttpResponse<?> response) {
    return wrap(request, response, CacheControl.parse(response.headers()));
  }

  static CachedHttpResponse wrap(HttpRequest<?> request, HttpResponse<?> response, CacheControl cacheControl) {
    return new CachedHttpResponse(
      response.version().name(),
      response.statusCode(),
      response.statusMessage(),
      response.bodyAsBuffer(),
      request.headers(),
      response.headers(),
      cacheControl
    );
  }

  CachedHttpResponse(String version, int statusCode, String statusMessage, Buffer body,
    MultiMap requestHeaders, MultiMap responseHeaders, CacheControl cacheControl) {
    this.version = version;
    this.statusCode = statusCode;
    this.statusMessage = statusMessage;
    this.body = body;
    this.requestHeaders = requestHeaders;
    this.responseHeaders = responseHeaders;
    this.timestamp = Instant.now(); // TODO: should we look at the Date or Age header instead?
    this.cacheControl = cacheControl;
    this.vary = new Vary(requestHeaders, responseHeaders);
  }

  public boolean isFresh() {
    return age() <= cacheControl().maxAge();
  }

  public boolean useWhileRevalidate() {
    long duration = cacheControl()
      .getTimeDirectives()
      .getOrDefault(CacheControlDirectives.STALE_WHILE_REVALIDATE, 0L);

    return age() <= duration;
  }

  public long age() {
    return Duration.between(timestamp, Instant.now()).getSeconds();
  }

  public CacheControl cacheControl() {
    if (cacheControl == null) {
      this.cacheControl = CacheControl.parse(responseHeaders);
    }
    return cacheControl;
  }

  public Vary vary() {
    if (vary == null) {
      this.vary = new Vary(requestHeaders, responseHeaders);
    }
    return vary;
  }

  public HttpResponse<Buffer> rehydrate() {
    return new HttpResponseImpl<>(
      HttpVersion.valueOf(version),
      statusCode,
      statusMessage,
      responseHeaders,
      MultiMap.caseInsensitiveMultiMap(),
      Collections.emptyList(),
      body,
      Collections.emptyList()
    );
  }

  private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
    ois.defaultReadObject();
    this.cacheControl = CacheControl.parse(responseHeaders);
    this.vary = new Vary(requestHeaders, responseHeaders);
  }
}
