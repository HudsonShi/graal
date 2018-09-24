/*
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.dsl.test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.dsl.test.SynchronizationTestFactory.NotifyNodeFactory;
import com.oracle.truffle.api.dsl.test.SynchronizationTestFactory.WaitNodeFactory;
import com.oracle.truffle.api.dsl.test.TypeSystemTest.ValueNode;
import com.oracle.truffle.api.dsl.test.examples.ExampleTypes;

public class SynchronizationTest {

    @TypeSystemReference(ExampleTypes.class)
    @NodeChildren({@NodeChild("monitor"), @NodeChild("latch")})
    abstract static class WaitNode extends ValueNode {

        public abstract Object executeEvaluated(Object monitor, CountDownLatch latch);

        @SuppressWarnings("deprecation")
        @Specialization
        Object doWait(Object monitor, CountDownLatch latch) {
            Assert.assertEquals(false, Thread.holdsLock(getAtomicLock()));
            final boolean holdsLock = ((ReentrantLock) getLock()).isHeldByCurrentThread();
            Assert.assertEquals(false, holdsLock);

            synchronized (monitor) {
                latch.countDown();
                try {
                    monitor.wait();
                } catch (InterruptedException e) {
                    throw new Error(e);
                }
            }
            return monitor;
        }

    }

    @TypeSystemReference(ExampleTypes.class)
    @NodeChildren({@NodeChild("monitor")})
    abstract static class NotifyNode extends ValueNode {

        public abstract Object executeEvaluated(Object monitor);

        @SuppressWarnings("deprecation")
        @Specialization
        Object doNotify(Object monitor) {
            Assert.assertEquals(false, Thread.holdsLock(getAtomicLock()));
            final boolean holdsLock = ((ReentrantLock) getLock()).isHeldByCurrentThread();
            Assert.assertEquals(false, holdsLock);

            synchronized (monitor) {
                monitor.notify();
            }
            return monitor;
        }

    }

    static class IfNode extends ValueNode {

        @Child WaitNode waitNode = WaitNodeFactory.create(null, null);
        @Child NotifyNode notifyNode = NotifyNodeFactory.create(null);

    }

    @Test
    public void testFirstExecutionDoesNotHoldLock() throws InterruptedException {
        final Object monitor = new Object();
        final CountDownLatch latch = new CountDownLatch(1);

        final IfNode ifNode = new IfNode();

        // We need a root node to get its lock
        @SuppressWarnings("unused")
        final CallTarget callTarget = TestHelper.createCallTarget(ifNode);

        Thread waitThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ifNode.waitNode.executeEvaluated(monitor, latch);
            }
        });
        waitThread.start();

        Thread notifyThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ifNode.notifyNode.executeEvaluated(monitor);
            }
        });

        latch.await();
        notifyThread.start();

        waitThread.join();
        notifyThread.join();
    }
}
