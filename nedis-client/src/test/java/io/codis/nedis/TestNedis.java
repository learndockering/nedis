/**
 * Copyright (c) 2015 CodisLabs.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.codis.nedis;

import static io.codis.nedis.TestUtils.cleanRedis;
import static io.codis.nedis.TestUtils.probeFreePort;
import static io.codis.nedis.TestUtils.waitUntilRedisUp;
import static io.codis.nedis.protocol.RedisCommand.GET;
import static io.codis.nedis.util.NedisUtils.bytesToString;
import static io.codis.nedis.util.NedisUtils.toBytes;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import io.codis.nedis.NedisClient;
import io.codis.nedis.NedisClientPool;
import io.codis.nedis.NedisClientPoolBuilder;
import io.codis.nedis.exception.RedisResponseException;
import io.codis.nedis.exception.TxnAbortException;
import io.codis.nedis.exception.TxnDiscardException;
import io.codis.nedis.util.NedisUtils;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.concurrent.Future;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Apache9
 */
public class TestNedis {

    private static int PORT = 80;

    //private static RedisServer REDIS;

    private NedisClientPool pool;

    private String hostName = "10.2.12.235";

    @BeforeClass
    public static void setUp() throws IOException, InterruptedException {
        /*PORT = probeFreePort();
        REDIS = new RedisServer(PORT);
        REDIS.start();
        waitUntilRedisUp(PORT);*/
    }

    @AfterClass
    public static void tearDownAfterClass() throws InterruptedException {
        //REDIS.stop();
    }

    @After
    public void tearDown() throws InterruptedException, IOException {
        if (pool != null) {
            pool.close();
        }
        cleanRedis(hostName,PORT);
    }

    @Test
    public void test() throws InterruptedException, ExecutionException {
        pool = NedisClientPoolBuilder.create()
                .remoteAddress(new InetSocketAddress(hostName, PORT)).clientName("test").build();
        NedisClient client = NedisUtils.newPooledClient(pool);
        System.out.println(client.toString());
        Future<String> pingFuture = client.ping();
        for (int i = 0; i < 1000; i++) {
            Future<Boolean> setFuture = client.set(toBytes("foo"), toBytes("bar" + i));
            assertTrue(setFuture.sync().getNow());
            assertEquals("bar" + i, bytesToString(client.get(toBytes("foo")).sync().getNow()));
        }
        assertEquals("PONG", pingFuture.sync().getNow());
        assertEquals(null, client.get(toBytes("bar")).sync().getNow());

        NedisClient pipelineClient = pool.acquire().sync().getNow();
        Future<Long> incrFuture = pipelineClient.incr(toBytes("num"));
        Future<Long> incrByFuture = pipelineClient.incrby(toBytes("num"), 2L);
        Future<Long> decrFuture = pipelineClient.decr(toBytes("num"));
        Future<Long> decrByFuture = pipelineClient.decrby(toBytes("num"), 2L);
        assertEquals(1L, incrFuture.sync().getNow().longValue());
        assertEquals(3L, incrByFuture.sync().getNow().longValue());
        assertEquals(2L, decrFuture.sync().getNow().longValue());
        assertEquals(0L, decrByFuture.sync().getNow().longValue());
        pipelineClient.release();

        client.mset(toBytes("a1"), toBytes("b1"), toBytes("a2"), toBytes("b2")).sync();

        List<byte[]> resp = client.mget(toBytes("a1"), toBytes("a2"), toBytes("a3")).sync()
                .getNow();
        assertEquals(3, resp.size());
        assertEquals("b1", bytesToString(resp.get(0)));
        assertEquals("b2", bytesToString(resp.get(1)));
        assertEquals(null, resp.get(2));

        assertEquals(pool.numConns(), pool.numPooledConns());
        int numConns = pool.numConns();
        Throwable error = client.execCmd(GET.raw).await().cause();
        error.printStackTrace();
        assertTrue(error instanceof RedisResponseException);

        // this error does not cause a connection closing.
        assertEquals(numConns, pool.numConns());
        assertEquals(numConns, pool.numPooledConns());

        client.close().sync();

        assertEquals(0, pool.numPooledConns());
        assertEquals(0, pool.numConns());
    }

    @Test
    public void testTimeout() throws InterruptedException {
        pool = NedisClientPoolBuilder.create()
                .remoteAddress(new InetSocketAddress(hostName, PORT)).database(1).build();
        NedisClient client = pool.acquire().sync().getNow();
        Thread.sleep(1000);
        assertEquals(1, pool.numPooledConns());
        assertEquals(1, pool.numConns());
        assertEquals(0L, client.setTimeout(100).sync().getNow().longValue());
        Future<?> future = client.blpop(1, toBytes("foo")).await();
        assertFalse(future.isSuccess());
        assertTrue(future.cause() instanceof ReadTimeoutException);
        Thread.sleep(1000);
        assertEquals(0, pool.numPooledConns());
        assertEquals(0, pool.numConns());
    }

    @Test
    public void testBlockingCommands() throws InterruptedException {
        pool = NedisClientPoolBuilder.create()
                .remoteAddress(new InetSocketAddress(hostName, PORT)).timeoutMs(100)
                .exclusive(true).build();
        NedisClient client = NedisUtils.newPooledClient(pool);
        Future<List<byte[]>> brpopFuture = client.brpop(100, toBytes("foo"));
        Thread.sleep(1000);
        assertFalse(brpopFuture.isDone());
        client.lpush(toBytes("foo"), toBytes("bar"));
        List<byte[]> brpopResp = brpopFuture.sync().getNow();
        assertEquals(2, brpopResp.size());
        assertEquals("foo", bytesToString(brpopResp.get(0)));
        assertEquals("bar", bytesToString(brpopResp.get(1)));

        Future<List<byte[]>> blpopFuture = client.blpop(100, toBytes("a1"));
        Future<byte[]> brpoplpushFuture = client.brpoplpush(toBytes("a2"), toBytes("a1"), 100);
        Thread.sleep(1000);
        assertFalse(blpopFuture.isDone());
        assertFalse(brpoplpushFuture.isDone());
        client.lpush(toBytes("a2"), toBytes("b"));

        List<byte[]> blpopResp = blpopFuture.sync().getNow();
        assertEquals(2, blpopResp.size());
        assertEquals("a1", bytesToString(blpopResp.get(0)));
        assertEquals("b", bytesToString(blpopResp.get(1)));

        assertTrue(brpoplpushFuture.isDone());
        assertEquals("b", bytesToString(brpoplpushFuture.getNow()));
    }

    private void testTxn(NedisClient txnClient, NedisClient chkClient) throws InterruptedException {
        Future<Void> multiFuture = txnClient.multi();
        Future<Boolean> setFuture1 = txnClient.set(toBytes("k1"), toBytes("v1"));
        Future<Boolean> setFuture2 = txnClient.set(toBytes("k2"), toBytes("v2"));
        Thread.sleep(1000);
        assertFalse(setFuture1.isDone());
        assertFalse(setFuture2.isDone());
        assertFalse(chkClient.exists(toBytes("k1")).sync().getNow().booleanValue());
        assertFalse(chkClient.exists(toBytes("k2")).sync().getNow().booleanValue());
        List<Object> execResult = txnClient.exec().sync().getNow();
        assertTrue(multiFuture.isDone());
        assertTrue(setFuture1.getNow().booleanValue());
        assertTrue(setFuture2.getNow().booleanValue());
        assertEquals(2, execResult.size());
        assertEquals("OK", execResult.get(0).toString());
        assertEquals("OK", execResult.get(1).toString());

        multiFuture = txnClient.multi();
        setFuture1 = txnClient.set(toBytes("k1"), toBytes("v3"));
        setFuture2 = txnClient.set(toBytes("k2"), toBytes("v4"));
        Thread.sleep(1000);
        assertFalse(setFuture1.isDone());
        assertFalse(setFuture2.isDone());
        assertEquals("v1", bytesToString(chkClient.get(toBytes("k1")).sync().getNow()));
        assertEquals("v2", bytesToString(chkClient.get(toBytes("k2")).sync().getNow()));
        txnClient.discard().sync();
        assertTrue(multiFuture.isDone());
        assertTrue(setFuture1.isDone());
        assertTrue(setFuture2.isDone());
        assertThat(setFuture1.cause(), is(instanceOf(TxnDiscardException.class)));
        assertThat(setFuture2.cause(), is(instanceOf(TxnDiscardException.class)));
        assertEquals("v1", bytesToString(chkClient.get(toBytes("k1")).sync().getNow()));
        assertEquals("v2", bytesToString(chkClient.get(toBytes("k2")).sync().getNow()));

        Future<Void> watchFuture = txnClient.watch(toBytes("k1"));
        multiFuture = txnClient.multi();
        setFuture1 = txnClient.set(toBytes("k1"), toBytes("v3"));
        execResult = txnClient.exec().sync().getNow();
        assertTrue(watchFuture.isDone());
        assertTrue(multiFuture.isDone());
        assertTrue(setFuture1.getNow().booleanValue());
        assertEquals(1, execResult.size());
        assertEquals("OK", execResult.get(0).toString());
        assertEquals("v3", bytesToString(chkClient.get(toBytes("k1")).sync().getNow()));

        txnClient.watch(toBytes("k1")).sync();
        multiFuture = txnClient.multi();
        setFuture1 = txnClient.set(toBytes("k1"), toBytes("v4"));
        assertTrue(chkClient.set(toBytes("k1"), toBytes("v1")).sync().getNow().booleanValue());
        execResult = txnClient.exec().sync().getNow();
        assertTrue(watchFuture.isDone());
        assertTrue(multiFuture.isDone());
        assertTrue(setFuture1.isDone());
        assertThat(setFuture1.cause(), is(instanceOf(TxnAbortException.class)));
        assertNull(execResult);
        assertEquals("v1", bytesToString(chkClient.get(toBytes("k1")).sync().getNow()));
    }

    @Test
    public void testTxn() throws InterruptedException {
        pool = NedisClientPoolBuilder.create()
                .remoteAddress(new InetSocketAddress(hostName, PORT)).exclusive(true).build();
        NedisClient client = pool.acquire().sync().getNow();
        NedisClient client2 = pool.acquire().sync().getNow();
        try {
            testTxn(client, client2);
        } finally {
            client.release();
            client2.release();
        }
    }

    @Test
    public void testInfiniteWaitWhenClosing() throws InterruptedException {
        pool = NedisClientPoolBuilder.create()
                .remoteAddress(new InetSocketAddress(hostName, PORT)).build();
        pool.close().sync();
    }
}
