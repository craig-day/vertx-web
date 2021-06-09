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
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.spi.CacheStore;
import io.vertx.ext.web.client.impl.HttpResponseImpl;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
  private final MultiMap headers;
  private final Instant timestamp;

  transient private final MultiMap trailers;
  transient private final List<String> cookies;
  transient private final List<String> redirects;
  transient private CacheControl cacheControl;
  transient private Vary vary;

  static CachedHttpResponse wrap(HttpResponse<?> response) {
    return new CachedHttpResponse(
      response.version().name(),
      response.statusCode(),
      response.statusMessage(),
      response.bodyAsBuffer(),
      response.headers(),
      response.trailers(),
      response.cookies(),
      response.followedRedirects()
    );
  }

  static CachedHttpResponse wrap(HttpResponse<?> response, CacheControl cacheControl) {
    return new CachedHttpResponse(
      response.version().name(),
      response.statusCode(),
      response.statusMessage(),
      response.bodyAsBuffer(),
      response.headers(),
      response.trailers(),
      response.cookies(),
      response.followedRedirects(),
      cacheControl
    );
  }

  CachedHttpResponse(String version, int statusCode, String statusMessage, Buffer body,
    MultiMap headers, MultiMap trailers, List<String> cookies,
    List<String> redirects) {
    this(version, statusCode, statusMessage, body, headers,
      trailers, cookies, redirects, CacheControl.parse(headers));
  }

  CachedHttpResponse(String version, int statusCode, String statusMessage, Buffer body,
    MultiMap headers, MultiMap trailers, List<String> cookies,
    List<String> redirects, CacheControl cacheControl) {
    this.version = version;
    this.statusCode = statusCode;
    this.statusMessage = statusMessage;
    this.body = body;
    this.headers = headers;
    this.timestamp = Instant.now(); // TODO: should we look at the Date or Age header instead?
    this.trailers = trailers;
    this.cookies = cookies;
    this.redirects = redirects;
    this.cacheControl = cacheControl;
    this.vary = new Vary(headers);
  }

  public boolean isFresh() {
    return age() <= cacheControl().maxAge();
  }

  public long age() {
    return Duration.between(timestamp, Instant.now()).getSeconds();
  }

  public MultiMap headers() {
    return headers;
  }

  public CacheControl cacheControl() {
    if (cacheControl == null) {
      this.cacheControl = CacheControl.parse(headers);
    }
    return cacheControl;
  }

  public Vary vary() {
    if (vary == null) {
      this.vary = new Vary(headers);
    }
    return vary;
  }

  public HttpResponse<Buffer> rehydrate() {
    return new HttpResponseImpl<>(
      HttpVersion.valueOf(version),
      statusCode,
      statusMessage,
      headers,
      trailers,
      cookies,
      body,
      redirects
    );
  }

  private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
    ois.defaultReadObject();
    this.cacheControl = CacheControl.parse(headers);
    this.vary = new Vary(headers);
  }
}
