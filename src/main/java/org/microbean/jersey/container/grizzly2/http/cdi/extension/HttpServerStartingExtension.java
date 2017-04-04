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
import java.util.Iterator;
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

import org.slf4j.Logger;

import static javax.interceptor.Interceptor.Priority.LIBRARY_AFTER;

/**
 * An {@link AbstractBlockingExtension} that searches for any {@link
 * HttpServer} instances in the current {@linkplain BeanManager CDI
 * container} and, for each one, {@linkplain HttpServer#start() starts
 * it}.
 *
 * <p>A {@linkplain #fireBlockingEvent(BeanManager) blocking event is
 * then fired} to keep the container up and running while the {@link
 * HttpServer}s are running.</p>
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see HttpServer
 *
 * @see HttpServer#start()
 *
 * @see AbstractBlockingExtension
 */
public class HttpServerStartingExtension extends AbstractBlockingExtension {


  /*
   * Instance fields.
   */


  /**
   * A {@link Collection} of {@link HttpServer}s that have been
   * {@linkplain HttpServer#start() started}.
   *
   * <p>This field is never {@code null}.</p>
   *
   * <h2>Thread Safety</h2>
   *
   * <p>This field is not safe for concurrent use by multiple
   * threads.</p>
   *
   * @see #startHttpServers(Object, BeanManager)
   */
  private final Collection<HttpServer> startedHttpServers;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link HttpServerStartingExtension}.
   *
   * @see AbstractBlockingExtension#AbstractBlockingExtension()
   */
  public HttpServerStartingExtension() {
    super();
    this.startedHttpServers = new LinkedList<>();
  }


  /*
   * Instance methods.
   */


  /**
   * Upon startup ({@linkplain Initialized initialization} of the
   * {@linkplain ApplicationScoped application scope}), looks for
   * {@link HttpServer} instances in the {@linkplain BeanManager CDI
   * container} represented by the supplied {@link BeanManager} and
   * {@linkplain HttpServer#start() starts them}.
   *
   * <p>If any {@link HttpServer}s were {@linkplain HttpServer#start()
   * started}, then a {@linkplain #fireBlockingEvent(BeanManager)
   * blocking event is fired}.</p>
   *
   * @param event the event signaling that the CDI container has
   * started; ignored; may be {@code null}
   *
   * @param beanManager a {@link BeanManager} supplied by the current
   * CDI container; may be {@code null} in which case no action will
   * be taken
   *
   * @exception IOException if an error occurs while {@linkplain
   * HttpServer#start() starting the <code>HttpServer</code>
   * instances}
   *
   * @see HttpServer#start()
   *
   * @see #fireBlockingEvent(BeanManager)
   */
  private final void startHttpServers(@Observes @Initialized(ApplicationScoped.class) @Priority(LIBRARY_AFTER) final Object event, final BeanManager beanManager) throws IOException {
    if (this.logger.isTraceEnabled()) {
      this.logger.trace("ENTRY {} {} {}, {}", this.getClass().getName(), "startHttpServers", event, beanManager);
    }
    if (beanManager != null) {
      final Instance<Object> beans = beanManager.createInstance();
      assert beans != null;
      final Instance<HttpServer> httpServers = beans.select(HttpServer.class);
      assert httpServers != null;
      if (!httpServers.isUnsatisfied()) {
        synchronized (this.startedHttpServers) {
          try {
            for (final HttpServer httpServer : httpServers) {
              if (httpServer != null) {

                // This (asynchronous) method starts a daemon thread
                // in the background; see
                // https://github.com/GrizzlyNIO/grizzly-mirror/blob/2.3.x/modules/http-server/src/main/java/org/glassfish/grizzly/http/server/HttpServer.java#L816
                // and work backwards to the start() method.  Among
                // other things, that won't prevent the JVM from
                // exiting normally.  Think about that for a while.
                // We'll need another mechanism to block the CDI
                // container from simply shutting down.
                httpServer.start();
                
                // We store our own list of started HttpServers to use
                // in other methods in this class rather than relying
                // on Instance<HttpServer> because we don't know what
                // scope the HttpServer instances in question are.
                // For example, they might be in Dependent scope,
                // which would mean every Instance#get() invocation
                // might create a new one.
                this.startedHttpServers.add(httpServer);
                
              }
            }
          } catch (final IOException throwMe) {
            if (!this.startedHttpServers.isEmpty()) {
              final Iterator<HttpServer> iterator = this.startedHttpServers.iterator();
              assert iterator != null;
              while (iterator.hasNext()) {
                final HttpServer httpServer = iterator.next();
                if (httpServer != null && httpServer.isStarted()) {
                  try {
                    httpServer.shutdownNow();
                    iterator.remove();
                  } catch (final RuntimeException problem) {
                    throwMe.addSuppressed(problem);
                  }                  
                }
              }
            }
            throw throwMe;
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
    if (logger != null && logger.isTraceEnabled()) {
      logger.trace("EXIT {} {}", this.getClass().getName(), "startHttpServers");
    }
  }

  /**
   * Calls {@link HttpServer#shutdownNow()} on any {@link HttpServer}
   * instances that were {@linkplain #startHttpServers(Object,
   * BeanManager) started}.
   *
   * @param event the event signaling that the current CDI container
   * is about to shut down; may be {@code null}; ignored
   *
   * @see #startHttpServers(Object, BeanManager)
   *
   * @see HttpServer#shutdownNow()
   */
  private final void stopHttpServers(@Observes final BeforeShutdown event) {
    synchronized (this.startedHttpServers) {
      final Iterator<HttpServer> iterator = this.startedHttpServers.iterator();
      assert iterator != null;
      while (iterator.hasNext()) {
        final HttpServer httpServer = iterator.next();
        iterator.remove();
        if (httpServer != null && httpServer.isStarted()) {          
          httpServer.shutdownNow();
        }
      }
    }
  }
  
}
