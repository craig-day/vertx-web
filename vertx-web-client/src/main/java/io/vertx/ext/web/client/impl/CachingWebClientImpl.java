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

import io.vertx.core.http.HttpClient;
import io.vertx.ext.web.client.CachingWebClientOptions;
import io.vertx.ext.web.client.impl.cache.CacheManager;

/**
 * @author <a href="mailto:craigday3@gmail.com">Craig Day</a>
 */
public class CachingWebClientImpl extends WebClientBase {

  public CachingWebClientImpl(HttpClient client, CacheManager cacheManager, CachingWebClientOptions options) {
    super(client, options);
    addInterceptor(new CachingInterceptor(cacheManager));
  }

  public CachingWebClientImpl(WebClientBase webClient, CacheManager cacheManager) {
    super(webClient);
    addInterceptor(new CachingInterceptor(cacheManager));
  }
}
