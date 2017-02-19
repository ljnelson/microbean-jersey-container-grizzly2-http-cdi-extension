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

import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.enterprise.event.ObservesAsync;

import javax.enterprise.inject.Instance;

import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeShutdown;
import javax.enterprise.inject.spi.Extension;

import javax.annotation.Priority;

import org.glassfish.grizzly.http.server.HttpServer;

import org.microbean.configuration.cdi.annotation.ConfigurationValue;

import static javax.interceptor.Interceptor.Priority.LIBRARY_AFTER;
import static javax.interceptor.Interceptor.Priority.LIBRARY_BEFORE;
import static javax.interceptor.Interceptor.Priority.PLATFORM_AFTER;
import static javax.interceptor.Interceptor.Priority.PLATFORM_BEFORE;

/**
 *
 * @author <a href="http://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see HttpServer
 *
 * @see HttpServer#start()
 */
public class HttpServerStartingExtension implements Extension {

  private final Collection<HttpServer> startedHttpServers;

  private final CountDownLatch latch;
  
  public HttpServerStartingExtension() {
    super();
    this.latch = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new ShutdownHook());
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
              httpServer.start(); // starts daemon
              // now store it away because we don't know what scope it's in; could be @Dependent
              this.startedHttpServers.add(httpServer);
            }
          }
          if (!this.startedHttpServers.isEmpty()) {
            beanManager.getEvent().select(ServersStarted.class).fireAsync(new ServersStarted());
          }
        }
      }
    }
  }

  private final void block(@ObservesAsync final ServersStarted event) throws InterruptedException {    
    this.latch.await();
  }

  void unblock() {
    this.latch.countDown();
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

  private final class ShutdownHook extends Thread {

    private ShutdownHook() {
      super();
    }

    @Override
    public final void run() {      
      unblock();
    }
    
  }

  private static final class ServersStarted {

    private ServersStarted() {
      super();
    }
    
  }
  
}
