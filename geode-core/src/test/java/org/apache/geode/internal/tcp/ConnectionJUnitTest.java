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
package org.apache.geode.internal.tcp;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.CancelCriterion;
import org.apache.geode.distributed.internal.DMStats;
import org.apache.geode.distributed.internal.DistributionManager;
import org.apache.geode.distributed.internal.membership.InternalDistributedMember;
import org.apache.geode.distributed.internal.membership.MembershipManager;
import org.apache.geode.internal.net.SocketCloser;
import org.apache.geode.internal.net.SocketCreator;
import org.apache.geode.test.junit.categories.MembershipTest;

@Category({MembershipTest.class})
public class ConnectionJUnitTest {

  /**
   * Test whether suspicion is raised about a member that closes its shared/unordered TCPConduit
   * connection
   */
  @Test
  public void testSuspicionRaised() throws Exception {
    // this test has to create a lot of mocks because Connection
    // uses a lot of objects

    // mock the socket
    ConnectionTable table = mock(ConnectionTable.class);
    DistributionManager distMgr = mock(DistributionManager.class);
    MembershipManager membership = mock(MembershipManager.class);
    TCPConduit conduit = mock(TCPConduit.class);

    // mock the connection table and conduit

    when(table.getConduit()).thenReturn(conduit);

    CancelCriterion stopper = mock(CancelCriterion.class);
    when(stopper.cancelInProgress()).thenReturn(null);
    when(conduit.getCancelCriterion()).thenReturn(stopper);

    when(conduit.getSocketId())
        .thenReturn(new InetSocketAddress(SocketCreator.getLocalHost(), 10337));

    // mock the distribution manager and membership manager
    when(distMgr.getMembershipManager()).thenReturn(membership);
    when(conduit.getDM()).thenReturn(distMgr);
    when(conduit.getStats()).thenReturn(mock(DMStats.class));
    when(table.getDM()).thenReturn(distMgr);
    SocketCloser closer = mock(SocketCloser.class);
    when(table.getSocketCloser()).thenReturn(closer);

    SocketChannel channel = SocketChannel.open();

    Connection conn = new Connection(table, channel.socket());
    conn.setSharedUnorderedForTest();
    conn.run();
    verify(membership).suspectMember(isNull(InternalDistributedMember.class), any(String.class));
  }
}
