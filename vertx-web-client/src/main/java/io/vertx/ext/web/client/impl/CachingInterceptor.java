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
package io.vertx.ext.web.client.impl;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.impl.cache.CacheManager;

/**
 * An interceptor for caching responses that operates on the {@link HttpContext}.
 *
 * @author <a href="mailto:craigday3@gmail.com">Craig Day</a>
 */
public class CachingInterceptor implements Handler<HttpContext<?>> {

  private final CacheManager manager;

  public CachingInterceptor(CacheManager manager) {
    this.manager = manager;
  }

  @Override
  public void handle(HttpContext<?> context) {
    switch (context.phase()) {
      case SEND_REQUEST:
        sendRequest((HttpContext<Buffer>) context);
        break;
      case RECEIVE_RESPONSE:
        receiveResponse((HttpContext<Buffer>) context);
        break;
      default:
        context.next();
        break;
    }
  }

  private void sendRequest(HttpContext<Buffer> context) {
    manager.processRequest(context.request()).onComplete(ar -> {
      if (ar.succeeded()) {
        context.dispatchResponse(ar.result());
      } else {
        context.next();
      }
    });
  }

  private void receiveResponse(HttpContext<Buffer> context) {
    manager.processResponse(context.request(), context.response()).onComplete(ar -> {
      if (ar.succeeded()) {
        context.dispatchResponse(ar.result());
      } else {
        context.next();
      }
    });
  }
}
