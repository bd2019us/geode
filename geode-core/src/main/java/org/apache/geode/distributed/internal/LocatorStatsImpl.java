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
package org.apache.geode.distributed.internal;

import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.geode.stats.common.distributed.internal.LocatorStats;
import org.apache.geode.stats.common.statistics.GFSStatsImplementer;
import org.apache.geode.stats.common.statistics.StatisticDescriptor;
import org.apache.geode.stats.common.statistics.Statistics;
import org.apache.geode.stats.common.statistics.StatisticsFactory;
import org.apache.geode.stats.common.statistics.StatisticsType;

/**
 * This class maintains statistics for the locator
 *
 * @since GemFire 5.7
 */
public class LocatorStatsImpl implements LocatorStats, GFSStatsImplementer {
  private static StatisticsType type;

  private static final String KNOWN_LOCATORS = LOCATORS; // gauge
  private static final String REQUESTS_TO_LOCATOR = "locatorRequests"; // counter
  private static final String RESPONSES_FROM_LOCATOR = "locatorResponses"; // counter
  private static final String ENDPOINTS_KNOWN = "servers"; // gauge
  private static final String REQUESTS_IN_PROGRESS = "requestsInProgress"; // gauge
  private static final String REQUEST_TIME = "requestProcessingTime"; // counter
  private static final String RESPONSE_TIME = "responseProcessingTime"; // counter
  private static final String SERVER_LOAD_UPDATES = "serverLoadUpdates"; // counter

  private AtomicInteger known_locators = new AtomicInteger();
  private AtomicLong requests_to_locator = new AtomicLong();
  private AtomicLong requestTime = new AtomicLong();
  private AtomicLong responseTime = new AtomicLong();
  private AtomicLong responses_from_locator = new AtomicLong();
  private AtomicInteger endpoints_known = new AtomicInteger();
  private AtomicInteger requestsInProgress = new AtomicInteger();
  private AtomicLong serverLoadUpdates = new AtomicLong();


  private int _KNOWN_LOCATORS;
  private int _REQUESTS_TO_LOCATOR;
  private int _RESPONSES_FROM_LOCATOR;
  private int _ENDPOINTS_KNOWN;
  private int _REQUESTS_IN_PROGRESS;
  private int _REQUEST_TIME;
  private int _RESPONSE_TIME;
  private int _SERVER_LOAD_UPDATES;

  private Statistics _stats = null;



  public void initializeStats(StatisticsFactory factory) {
    String statName = "LocatorStats";
    String statDescription = "Statistics on the gemfire locator.";
    String serverThreadsDesc =
        "The number of location requests currently being processed by the thread pool.";
    type = factory.createType(statName, statDescription, new StatisticDescriptor[] {
        factory.createIntGauge(KNOWN_LOCATORS, "Number of locators known to this locator",
            LOCATORS),
        factory.createLongCounter(REQUESTS_TO_LOCATOR,
            "Number of requests this locator has received from clients", "requests"),
        factory.createLongCounter(RESPONSES_FROM_LOCATOR,
            "Number of responses this locator has sent to clients", "responses"),
        factory.createIntGauge(ENDPOINTS_KNOWN, "Number of servers this locator knows about",
            "servers"),
        factory.createIntGauge(REQUESTS_IN_PROGRESS, serverThreadsDesc, "requests"),
        factory.createLongCounter(REQUEST_TIME, "Time spent processing server location requests",
            "nanoseconds"),
        factory.createLongCounter(RESPONSE_TIME, "Time spent sending location responses to clients",
            "nanoseconds"),
        factory.createLongCounter(SERVER_LOAD_UPDATES,
            "Total number of times a server load update has been received.", "updates"),});

    _REQUESTS_IN_PROGRESS = type.nameToId(REQUESTS_IN_PROGRESS);
    _KNOWN_LOCATORS = type.nameToId(KNOWN_LOCATORS);
    _REQUESTS_TO_LOCATOR = type.nameToId(REQUESTS_TO_LOCATOR);
    _RESPONSES_FROM_LOCATOR = type.nameToId(RESPONSES_FROM_LOCATOR);
    _ENDPOINTS_KNOWN = type.nameToId(ENDPOINTS_KNOWN);
    _REQUEST_TIME = type.nameToId(REQUEST_TIME);
    _RESPONSE_TIME = type.nameToId(RESPONSE_TIME);
    _SERVER_LOAD_UPDATES = type.nameToId(SERVER_LOAD_UPDATES);
  }

  /**
   * Creates a new <code>LocatorStats</code> and registers itself with the given statistics factory.
   */
  public LocatorStatsImpl(StatisticsFactory factory, String identifier) {
    initializeStats(factory);
  }

  /**
   * Called when the DS comes online so we can hookup the stats
   */
  @Override
  public void hookupStats(String name) {
    // if (this._stats == null) {
    // this._stats = f.createAtomicStatistics(type, name);
    // setLocatorCount(known_locators.get());
    // setServerCount(endpoints_known.get());
    // setLocatorRequests(requests_to_locator.get());
    // setLocatorResponses(responses_from_locator.get());
    // setServerLoadUpdates(serverLoadUpdates.get());
    // }
  }


  @Override
  public void setServerCount(int sc) {
    if (this._stats == null) {
      this.endpoints_known.set(sc);
    } else {
      this._stats.setInt(_ENDPOINTS_KNOWN, sc);
    }
  }

  @Override
  public void setLocatorCount(int lc) {
    if (this._stats == null) {
      this.known_locators.set(lc);
    } else {
      this._stats.setInt(_KNOWN_LOCATORS, lc);
    }
  }

  @Override
  public void endLocatorRequest(long startTime) {
    long took = System.nanoTime() - startTime;
    if (this._stats == null) {
      this.requests_to_locator.incrementAndGet();
      if (took > 0) {
        this.requestTime.getAndAdd(took);
      }
    } else {
      this._stats.incLong(_REQUESTS_TO_LOCATOR, 1);
      if (took > 0) {
        this._stats.incLong(_REQUEST_TIME, took);
      }
    }
  }

  @Override
  public void endLocatorResponse(long startTime) {
    long took = System.nanoTime() - startTime;
    if (this._stats == null) {
      this.responses_from_locator.incrementAndGet();
      if (took > 0) {
        this.responseTime.getAndAdd(took);
      }
    } else {
      this._stats.incLong(_RESPONSES_FROM_LOCATOR, 1);
      if (took > 0) {
        this._stats.incLong(_RESPONSE_TIME, took);
      }
    }
  }



  @Override
  public void setLocatorRequests(long rl) {
    if (this._stats == null) {
      this.requests_to_locator.set(rl);
    } else {
      this._stats.setLong(_REQUESTS_TO_LOCATOR, rl);
    }
  }

  @Override
  public void setLocatorResponses(long rl) {
    if (this._stats == null) {
      this.responses_from_locator.set(rl);
    } else {
      this._stats.setLong(_RESPONSES_FROM_LOCATOR, rl);
    }
  }

  @Override
  public void setServerLoadUpdates(long v) {
    if (this._stats == null) {
      this.serverLoadUpdates.set(v);
    } else {
      this._stats.setLong(_SERVER_LOAD_UPDATES, v);
    }
  }

  @Override
  public void incServerLoadUpdates() {
    if (this._stats == null) {
      this.serverLoadUpdates.incrementAndGet();
    } else {
      this._stats.incLong(_SERVER_LOAD_UPDATES, 1);
    }
  }

  @Override
  public void incRequestInProgress(int threads) {
    if (this._stats != null) {
      this._stats.incInt(_REQUESTS_IN_PROGRESS, threads);
    } else {
      requestsInProgress.getAndAdd(threads);
    }
  }

  @Override
  public void close() {
    if (this._stats != null) {
      this._stats.close();
    }
  }
}