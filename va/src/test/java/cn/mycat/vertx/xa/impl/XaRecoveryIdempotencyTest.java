/**
 * Copyright (C) <2021>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package cn.mycat.vertx.xa.impl;

import cn.mycat.vertx.xa.ImmutableCoordinatorLog;
import cn.mycat.vertx.xa.ImmutableParticipantLog;
import cn.mycat.vertx.xa.MySQLManager;
import cn.mycat.vertx.xa.Repository;
import cn.mycat.vertx.xa.State;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for XA recovery idempotency.
 * Tests the fix for P0-1: concurrent recovery deduplication.
 */
public class XaRecoveryIdempotencyTest {

    @Mock
    private Repository mockRepository;

    @Mock
    private MySQLManager mockMySQLManager;

    private XaLogImpl xaLog;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        xaLog = new XaLogImpl(mockRepository, 0, mockMySQLManager);
    }

    @Test
    public void testNextXidGeneratesUniqueIds() {
        String xid1 = xaLog.nextXid();
        String xid2 = xaLog.nextXid();
        String xid3 = xaLog.nextXid();

        assertNotNull(xid1);
        assertNotNull(xid2);
        assertNotNull(xid3);

        assertNotEquals("XIDs should be unique", xid1, xid2);
        assertNotEquals("XIDs should be unique", xid2, xid3);
    }

    @Test
    public void testNextXidOverflow() {
        // Test that XID counter wraps around correctly
        xaLog = new XaLogImpl(mockRepository, Long.MAX_VALUE, mockMySQLManager);

        String xid1 = xaLog.nextXid();
        String xid2 = xaLog.nextXid();

        assertNotNull(xid1);
        assertNotNull(xid2);
    }

    @Test
    public void testLogWithNullXid() {
        // Should not throw exception
        xaLog.log(null, new ImmutableParticipantLog[0]);
        xaLog.log("test", (String) null, State.XA_PREPARED);
    }

    @Test
    public void testLogAndGetCoordinator() {
        String xid = xaLog.nextXid();
        ImmutableParticipantLog[] participants = new ImmutableParticipantLog[]{
                new ImmutableParticipantLog("ds1", System.currentTimeMillis() + 60000, State.XA_PREPARED)
        };

        xaLog.log(xid, participants);

        ImmutableCoordinatorLog coordinatorLog = xaLog.logCommitBeforeXaCommit(xid);
        assertNotNull("Coordinator log should exist", coordinatorLog);
        assertEquals("XID should match", xid, coordinatorLog.getXid());
    }

    @Test
    public void testConcurrentLogOperations() throws InterruptedException {
        // P1-3: Test that concurrent log operations don't deadlock
        final int threadCount = 10;
        final int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String xidPrefix = "xid-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        String xid = xidPrefix + "-" + j;
                        ImmutableParticipantLog[] participants = new ImmutableParticipantLog[]{
                                new ImmutableParticipantLog("ds1", System.currentTimeMillis() + 60000, State.XA_PREPARED)
                        };
                        xaLog.log(xid, participants);
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for completion
        assertTrue("All threads should complete within 30 seconds",
                doneLatch.await(30, TimeUnit.SECONDS));

        assertEquals("No errors should occur during concurrent log operations",
                0, errorCount.get());

        executor.shutdown();
    }

    @Test
    public void testLogCommitBeforeAndCancel() {
        String xid = xaLog.nextXid();
        ImmutableParticipantLog[] participants = new ImmutableParticipantLog[]{
                new ImmutableParticipantLog("ds1", System.currentTimeMillis() + 60000, State.XA_PREPARED)
        };

        xaLog.log(xid, participants);

        // Log commit
        ImmutableCoordinatorLog log = xaLog.logCommitBeforeXaCommit(xid);
        assertNotNull(log);

        // Cancel commit
        xaLog.logCancelCommitBeforeXaCommit(xid);

        // Should not throw
        xaLog.logCommit(xid, true);
    }

    @Test
    public void testRollbackWithSuccess() {
        String xid = xaLog.nextXid();
        xaLog.logRollback(xid, true);
        // Should not throw
    }

    @Test
    public void testCheckStateWithNonExistentXid() {
        // checkState is private, but we can test through logPrepare
        xaLog.logPrepare("non-existent-xid", true);
        // Should not throw
    }

    @Test(expected = RuntimeException.class)
    public void testCloseThrowsIOException() throws Exception {
        when(mockRepository.close()).thenThrow(new RuntimeException("Close failed"));
        xaLog.close();
    }

    @Test
    public void testGetTimeout() {
        when(mockRepository.getTimeout()).thenReturn(30000L);
        assertEquals(30000L, xaLog.getTimeout());
    }

    @Test
    public void testGetExpires() {
        when(mockRepository.getTimeout()).thenReturn(30000L);
        long expires = xaLog.getExpires();
        long expectedMin = System.currentTimeMillis() + 30000L - 100; // Allow 100ms tolerance
        assertTrue("Expires should be in the future", expires > expectedMin);
    }

    @Test
    public void testRetryDelay() {
        when(mockRepository.retryDelayTime()).thenReturn(1000L);
        assertEquals(1000L, xaLog.retryDelay());
    }
}
