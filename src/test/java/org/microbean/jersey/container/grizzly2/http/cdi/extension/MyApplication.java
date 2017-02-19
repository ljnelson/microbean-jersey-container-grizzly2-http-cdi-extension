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

import javax.inject.Inject;

import javax.ws.rs.core.Application;

import org.microbean.configuration.cdi.annotation.ConfigurationValue;

import static org.junit.Assert.assertNotNull;

public class MyApplication extends Application {

  @Inject
  public MyApplication(@ConfigurationValue(value = "frobnicationStyle", defaultValue = "caturgiation") final String gorp) {
    super();
    assertNotNull(gorp);
  }
  
}
