/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2017 MicroBean.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.jersey.container.grizzly2.http.cdi.extension;

import java.io.IOException;

import java.util.Collection;
import java.util.LinkedList;

import java.util.concurrent.CountDownLatch;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Initialized;

import javax.enterprise.event.Observes;
import javax.enterprise.event.ObservesAsync;

import javax.enterprise.inject.Instance;

import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeShutdown;
import javax.enterprise.inject.spi.Extension;

import javax.annotation.Priority;

import org.glassfish.grizzly.http.server.HttpServer;

import org.microbean.cdi.AbstractBlockingExtension;

import static javax.interceptor.Interceptor.Priority.LIBRARY_AFTER;

/**
 *
 * @author <a href="http://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see HttpServer
 *
 * @see HttpServer#start()
 */
public class HttpServerStartingExtension extends AbstractBlockingExtension {

  private final Collection<HttpServer> startedHttpServers;

  public HttpServerStartingExtension() {
    super(new CountDownLatch(1));
    this.startedHttpServers = new LinkedList<>();
  }

  private final void startHttpServers(@Observes @Initialized(ApplicationScoped.class) @Priority(LIBRARY_AFTER) final Object event, final BeanManager beanManager) throws IOException, InterruptedException {
    if (beanManager != null) {
      final Instance<Object> beans = beanManager.createInstance();
      assert beans != null;
      final Instance<HttpServer> httpServers = beans.select(HttpServer.class);
      assert httpServers != null;
      if (!httpServers.isUnsatisfied()) {
        synchronized (this.startedHttpServers) {
          for (final HttpServer httpServer : httpServers) {
            if (httpServer != null) {
              // This asynchronous method starts a daemon thread in
              // the background; see
              // https://github.com/GrizzlyNIO/grizzly-mirror/blob/2.3.x/modules/http-server/src/main/java/org/glassfish/grizzly/http/server/HttpServer.java#L816
              // and work backwards to the start() method.  Among
              // other things, that won't prevent the JVM from exiting
              // normally.  Think about that for a while.  We'll need
              // another mechanism to block the CDI container from
              // simply shutting down.
              httpServer.start();

              // We store our own list of started HttpServers to use
              // in other methods in this class rather than relying on
              // Instance<HttpServer> because we don't know what scope
              // the HttpServer instances in question are.  For
              // example, they might be in Dependent scope, which
              // would mean every Instance#get() invocation might
              // create a new one.
              this.startedHttpServers.add(httpServer);
            }
          }
          if (!this.startedHttpServers.isEmpty()) {
            // Here we fire an *asynchronous* event that will cause
            // the thread it is received on to block (see the block()
            // method below).  This is key: the JVM will be prevented
            // from exiting, and the thread that the event is received
            // on is (a) a non-daemon thread and (b) managed by the
            // container.  This is, in other words, a clever way to
            // take advantage of the CDI container's mandated thread
            // management behavior so that the CDI container stays up
            // for at least as long as the HttpServer we spawned
            // above.
            this.fireBlockingEvent(beanManager);
          }
        }
      }
    }
  }

  private final void stopHttpServers(@Observes final BeforeShutdown event) {
    synchronized (this.startedHttpServers) {
      for (final HttpServer httpServer : this.startedHttpServers) {
        if (httpServer != null && httpServer.isStarted()) {
          httpServer.shutdownNow();
        }
      }
    }
  }
  
}
