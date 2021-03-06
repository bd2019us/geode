/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.management.internal.rest;

import static org.apache.geode.test.junit.assertions.ClusterManagementResultAssert.assertManagementResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.context.WebApplicationContext;

import org.apache.geode.cache.configuration.BasicRegionConfig;
import org.apache.geode.cache.configuration.RegionConfig;
import org.apache.geode.cache.configuration.RegionType;
import org.apache.geode.management.api.ClusterManagementResult;
import org.apache.geode.management.api.ClusterManagementService;
import org.apache.geode.management.client.ClusterManagementServiceProvider;

@RunWith(SpringRunner.class)
@ContextConfiguration(locations = {"classpath*:WEB-INF/geode-management-servlet.xml"},
    loader = PlainLocatorContextLoader.class)
@WebAppConfiguration
public class RegionManagementIntegrationTest {

  @Autowired
  private WebApplicationContext webApplicationContext;

  // needs to be used together with any BaseLocatorContextLoader
  private LocatorWebContext context;

  private ClusterManagementService client;

  @Before
  public void before() {
    context = new LocatorWebContext(webApplicationContext);
    client = ClusterManagementServiceProvider.getService(context.getRequestFactory());
  }

  @Test
  @WithMockUser
  public void sanityCheck() throws Exception {
    BasicRegionConfig regionConfig = new BasicRegionConfig();
    regionConfig.setName("customers");
    regionConfig.setType(RegionType.REPLICATE);

    assertManagementResult(client.create(regionConfig))
        .failed()
        .hasStatusCode(ClusterManagementResult.StatusCode.ERROR)
        .containsStatusMessage("no members found in cluster to create cache element");
  }

  @Test
  @WithMockUser
  public void invalidType() throws Exception {
    BasicRegionConfig regionConfig = new BasicRegionConfig();
    regionConfig.setName("customers");
    regionConfig.setType("LOCAL");

    assertManagementResult(client.create(regionConfig))
        .failed()
        .hasStatusCode(ClusterManagementResult.StatusCode.ILLEGAL_ARGUMENT)
        .containsStatusMessage("Type LOCAL is not supported in Management V2 API.");
  }

  @Test
  public void invalidGroup() throws Exception {
    BasicRegionConfig regionConfig = new BasicRegionConfig();
    regionConfig.setName("customers");
    regionConfig.setGroup("cluster");

    assertManagementResult(client.create(regionConfig))
        .failed()
        .hasStatusCode(ClusterManagementResult.StatusCode.ILLEGAL_ARGUMENT)
        .containsStatusMessage("cluster is a reserved group name");
  }

  @Test
  public void invalidConfigObject() throws Exception {
    RegionConfig config = new RegionConfig();
    config.setName("customers");
    assertManagementResult(client.create(config))
        .failed()
        .hasStatusCode(ClusterManagementResult.StatusCode.ILLEGAL_ARGUMENT)
        .containsStatusMessage("Use BasicRegionConfig to configure your region");
  }


  @Test
  @WithMockUser
  public void ping() throws Exception {
    context.perform(get("/v2/ping"))
        .andExpect(content().string("pong"));
  }
}
