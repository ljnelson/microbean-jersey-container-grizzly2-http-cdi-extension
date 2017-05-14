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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.BeforeDestroyed;
import javax.enterprise.context.Initialized;

import javax.enterprise.event.Observes;
import javax.enterprise.event.ObservesAsync;

import javax.enterprise.inject.Instance;

import javax.enterprise.inject.spi.BeanManager;
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
 * <p>The CDI container is {@linkplain AbstractBlockingExtension
 * blocked politely} to keep the container up and running while the
 * {@link HttpServer}s are running.</p>
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
   * started}, then the CDI container is {@linkplain
   * AbstractBlockingExtension politely blocked} until they are
   * {@linkplain HttpServer#shutdown() stopped}.</p>
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
   * @see AbstractBlockingExtension
   */
  private final void startHttpServers(@Observes
                                      @Initialized(ApplicationScoped.class)
                                      @Priority(LIBRARY_AFTER - 1)
                                      final Object event,
                                      final BeanManager beanManager)
    throws IOException {
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
                if (this.logger.isInfoEnabled()) {
                  this.logger.info("Starting HttpServer: {}", httpServer);
                }
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
          } catch (final IOException startMethodFailed) {
            try {
              this.stopHttpServers(startMethodFailed, false /* forceRemove */);
            } catch (final ExecutionException | InterruptedException suppressMe) {
              if (suppressMe instanceof InterruptedException) {
                Thread.currentThread().interrupt();
              }
              startMethodFailed.addSuppressed(suppressMe);
            }
            throw startMethodFailed;
          }
          
          if (this.startedHttpServers.isEmpty()) {
            this.unblock();
          }
          
        }
      }
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {} {}", this.getClass().getName(), "startHttpServers");
    }
  }

  /**
   * Calls {@link HttpServer#shutdown()} on any {@link HttpServer}
   * instances that were {@linkplain #startHttpServers(Object,
   * BeanManager) started}.
   *
   * @param event the event signaling that the current CDI container
   * is about to shut down; may be {@code null}; ignored
   *
   * @exception ExecutionException if there was a problem {@linkplain
   * HttpServer#shutdown() shutting down} an {@link HttpServer}
   *
   * @exception InterruptedException if the {@link Thread} performing
   * the shutdown was interrupted
   *
   * @see #startHttpServers(Object, BeanManager)
   *
   * @see HttpServer#shutdownNow()
   */
  private final void stopHttpServers(@Observes
                                     @BeforeDestroyed(ApplicationScoped.class)
                                     final Object event)
    throws ExecutionException, InterruptedException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {} {} {}", this.getClass().getName(), "stopHttpServers", event);
    }
    try {
      this.stopHttpServers(null /* no IOException */, event != null /* forceRemove */);
    } catch (final IOException willNeverHappen) {
      throw new AssertionError("Unexpected theoretically impossible java.io.IOException", willNeverHappen);
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {} {}", this.getClass().getName(), "stopHttpServers");
    }
  }

  /**
   * Calls {@link HttpServer#shutdown()} on any {@link HttpServer}
   * instances that were {@linkplain #startHttpServers(Object,
   * BeanManager) started}.
   *
   * @param startMethodFailed an {@link IOException} that may have
   * caused this method to be invoked; if non-{@code null} then any
   * exceptions encountered during the shutdown process will be
   * {@linkplain Throwable#addSuppressed(Throwable) added to it}
   * before it is rethrown; may be {@code null}
   *
   * @param forceRemove whether to forcibly remove the {@link HttpServer}
   * from an internal list of started {@link HttpServer}s regardless of
   * whether an attempt to shut it down succeeded
   *
   * @exception ExecutionException if {@code startMethodFailed} is
   * {@code null} and the {@link HttpServer#shutdown()} method
   * resulted in a {@link Future} whose {@link Future#get()} method
   * threw an {@link ExecutionException}
   *
   * @exception InterruptedException if {@code startMethodFailed} is
   * {@code null} and the {@link Thread} performing the shutdown was
   * interrupted
   *
   * @exception IOException if {@code startMethodFailed} is non-{@code
   * null}
   *
   * @see #startHttpServers(Object, BeanManager)
   *
   * @see HttpServer#shutdownNow()
   */
  private final void stopHttpServers(final IOException startMethodFailed,
                                     final boolean forceRemove)
    throws ExecutionException, InterruptedException, IOException {
    if (this.logger.isTraceEnabled()) {
      this.logger.trace("ENTRY {} {} {}, {}", this.getClass().getName(), "stopHttpServers", startMethodFailed, forceRemove);
    }
    synchronized (this.startedHttpServers) {
      if (!this.startedHttpServers.isEmpty()) {
        final Iterator<HttpServer> iterator = this.startedHttpServers.iterator();
        assert iterator != null;
        while (iterator.hasNext()) {
          final HttpServer httpServer = iterator.next();
          if (forceRemove) {
            iterator.remove();
          }
          if (httpServer != null && httpServer.isStarted()) {
            try {
              final Future<HttpServer> shutdownFuture = httpServer.shutdown();
              assert shutdownFuture != null;
              final HttpServer stoppedServer = shutdownFuture.get();
              if (!forceRemove) {
                iterator.remove();
              }
              assert stoppedServer == httpServer;
              assert !httpServer.isStarted();
            } catch (final ExecutionException | InterruptedException | RuntimeException problem) {
              if (problem instanceof InterruptedException) {
                Thread.currentThread().interrupt();
              }
              if (startMethodFailed != null) {
                startMethodFailed.addSuppressed(problem);
              } else {
                throw problem;
              }
            }
          }
        }
      }
      if (startMethodFailed == null) {
        if (logger.isDebugEnabled()) {
          logger.debug("Stopped HttpServer instances because of a normal shutdown");
        }
      } else {
        if (logger.isErrorEnabled()) {
          logger.error("Stopped HttpServer instances because of a java.io.IOException", startMethodFailed);
        }
        throw startMethodFailed;
      }
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {} {}", this.getClass().getName(), "stopHttpServers");
    }
  }
  
}
