/*
 * $Header: /home/jerenkrantz/tmp/commons/commons-convert/cvs/home/cvs/jakarta-commons//transaction/src/test/org/apache/commons/transaction/locking/GenericLockTest.java,v 1.10 2005/01/07 12:41:58 ozeigermann Exp $
 * $Revision: 1.10 $
 * $Date: 2005/01/07 12:41:58 $
 *
 * ====================================================================
 *
 * Copyright 2004 The Apache Software Foundation 
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
 *
 */

package org.apache.commons.transaction.locking;

import java.io.PrintWriter;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.apache.commons.transaction.util.LoggerFacade;
import org.apache.commons.transaction.util.PrintWriterLogger;
import org.apache.commons.transaction.util.RendezvousBarrier;
import org.apache.commons.transaction.util.TurnBarrier;

/**
 * Tests for generic locks. 
 *
 * @version $Revision: 1.10 $
 */
public class GenericLockTest extends TestCase {

    private static final LoggerFacade sLogger = new PrintWriterLogger(new PrintWriter(System.out),
            GenericLockTest.class.getName(), false);

    protected static final int READ_LOCK = 1;
    protected static final int WRITE_LOCK = 2;
    
    private static final int CONCURRENT_TESTS = 25;
    
    protected static final long TIMEOUT = 1000000;
    
    private static int deadlockCnt = 0;
    private static String first = null;

    public static Test suite() {
        TestSuite suite = new TestSuite(GenericLockTest.class);
        return suite;
    }

    public static void main(java.lang.String[] args) {
        junit.textui.TestRunner.run(suite());
    }

    public GenericLockTest(String testName) {
        super(testName);
    }

    // we do not wait, as we only want the check the results and do not want real locking
    protected boolean acquireNoWait(GenericLock lock, String owner, int targetLockLevel)  {
        try {
            return lock.acquire(owner, targetLockLevel, false, true, -1);
        } catch (InterruptedException e) {
            return false;
        }
    }
    
    public void testBasic() throws Throwable {

        sLogger.logInfo("\n\nChecking basic map features\n\n");

        String owner1 = "owner1";
        String owner2 = "owner2";
        String owner3 = "owner3";
        
        // a read / write lock
        GenericLock lock = new GenericLock("Test read write lock", WRITE_LOCK, sLogger);
        
        // of course more than one can read
        boolean canRead1 = acquireNoWait(lock, owner1, READ_LOCK);
        assertTrue(canRead1);
        boolean canRead2 = acquireNoWait(lock, owner2, READ_LOCK);
        assertTrue(canRead2);
        
        // as there already are read locks, this write should not be possible
        boolean canWrite3 = acquireNoWait(lock, owner3, WRITE_LOCK);
        assertFalse(canWrite3);
        
        // release one read lock
        lock.release(owner2);
        // this should not change anything with the write as there is still one read lock left
        canWrite3 = acquireNoWait(lock, owner3, WRITE_LOCK);
        assertFalse(canWrite3);

        // release the other and final read lock as well
        lock.release(owner1);
        // no we should be able to get write access
        canWrite3 = acquireNoWait(lock, owner3, WRITE_LOCK);
        assertTrue(canWrite3);
        // but of course no more read access
        canRead2 = acquireNoWait(lock, owner2, READ_LOCK);
        assertFalse(canRead2);
        
        // relase the write lock and make sure we can read again
        lock.release(owner3);
        canRead2 = acquireNoWait(lock, owner2, READ_LOCK);
        assertTrue(canRead2);
        
        // now we do something weired, we try to block all locks lower than write...
        boolean canBlock3 = lock.acquire(owner3, WRITE_LOCK, false, GenericLock.COMPATIBILITY_SUPPORT, -1);
        // which of course does not work, as there already is an incompatible read lock
        assertFalse(canBlock3);
        
        // ok, release read lock (no we have no more locks) and try again
        lock.release(owner2);
        canBlock3 = lock.acquire(owner3, WRITE_LOCK, false, GenericLock.COMPATIBILITY_SUPPORT, -1);
        // which now should work creating an ordinary lock
        assertTrue(canBlock3);
        
        // as this just an ordinary lock, we should not get a read lock:
        canRead1 = acquireNoWait(lock, owner1, READ_LOCK);
        assertFalse(canRead1);
        
        // this is the trick now, we *can* get an addtional write lock with this request as it has
        // the same level as the write lock already set. This works, as we do not care for the
        // write lock level, but only want to inhibit the read lock:
        boolean canBlock2 = lock.acquire(owner2, WRITE_LOCK, false, GenericLock.COMPATIBILITY_SUPPORT, -1);
        assertTrue(canBlock2);
        
        // now if we release one of the blocks supporting each other we still should not get a
        // read lock
        lock.release(owner3);
        canRead1 = acquireNoWait(lock, owner1, READ_LOCK);
        assertFalse(canRead1);

        // but of course after we release the second as well
        lock.release(owner2);
        canRead1 = acquireNoWait(lock, owner1, READ_LOCK);
        assertTrue(canRead1);
    }

    public void testDeadlock() throws Throwable {

        sLogger.logInfo("\n\nChecking deadlock detection\n\n");

        final String owner1 = "owner1";
        final String owner2 = "owner2";

        final String res1 = "res1";
        final String res2 = "res2";

        // a read / write lock
        final ReadWriteLockManager manager = new ReadWriteLockManager(sLogger, TIMEOUT);
        
        final RendezvousBarrier restart = new RendezvousBarrier("restart",
                TIMEOUT, sLogger);

        for (int i = 0; i < CONCURRENT_TESTS; i++) {

           System.out.print(".");
            
            final RendezvousBarrier deadlockBarrier1 = new RendezvousBarrier("deadlock1" + i,
                    TIMEOUT, sLogger);

            Thread deadlock = new Thread(new Runnable() {
                public void run() {
                    try {
                        // first both threads get a lock, this one on res2
                        manager.writeLock(owner2, res2);
                        synchronized (deadlockBarrier1) {
                            deadlockBarrier1.meet();
                            deadlockBarrier1.reset();
                        }
                        // if I am first, the other thread will be dead, i.e.
                        // exactly one
                        manager.writeLock(owner2, res1);
                    } catch (LockException le) {
                        assertEquals(le.getCode(), LockException.CODE_DEADLOCK_VICTIM);
                        deadlockCnt++;
                    } catch (InterruptedException ie) {
                    } finally {
                        manager.releaseAll(owner2);
                        try {
                            synchronized (restart) {
                                restart.meet();
                                restart.reset();
                            }
                            } catch (InterruptedException ie) {}
                    }
                }
            }, "Deadlock Thread");

            deadlock.start();

            try {
                // first both threads get a lock, this one on res2
                manager.readLock(owner1, res1);
                synchronized (deadlockBarrier1) {
                    deadlockBarrier1.meet();
                    deadlockBarrier1.reset();
                }
                //          if I am first, the other thread will be dead, i.e. exactly
                // one
                manager.readLock(owner1, res2);
            } catch (LockException le) {
                assertEquals(le.getCode(), LockException.CODE_DEADLOCK_VICTIM);
                deadlockCnt++;
            } finally {
                manager.releaseAll(owner1);
                synchronized (restart) {
                    restart.meet();
                    restart.reset();
                }
            }

            assertEquals(1, deadlockCnt);
            deadlockCnt = 0;
        }
    }

    /*
     * 
     * Test shows the following
     * - upgrade works with read locks no matter if they are acquired before or later (1-4)
     * - write is blocked by read (5)
     * - read is blocked by intention lock (6)
     * - write lock coming from an intention lock always has preference over others (7)
     * 
     * 
     *                  Owner           Owner           Owner
     * Step             #1              #2              #3
     * 1                read (ok)
     * 2                                upgrade (ok)
     * 3                release (ok)
     * 4                read (ok)
     * 5                                write (blocked 
     *                                  because of #1)
     * 6                                                read (blocked 
     *                                                  because intention of #2)        
     * 7                release         resumed
     * 8                                release         resumed
     * 9                                                release
     */
    public void testUpgrade() throws Throwable {

        sLogger.logInfo("\n\nChecking upgrade and preference lock\n\n");
        
        final String owner1 = "owner1";
        final String owner2 = "owner2";
        final String owner3 = "owner3";

        final String res1 = "res1";

        // a read / write lock
        final ReadWriteUpgradeLockManager manager = new ReadWriteUpgradeLockManager(sLogger,
                TIMEOUT);

        final RendezvousBarrier restart = new RendezvousBarrier("restart", 3, TIMEOUT, sLogger);

        final TurnBarrier cb = new TurnBarrier("cb1", TIMEOUT, sLogger, 1);

        for (int i = 0; i < CONCURRENT_TESTS; i++) {
            
            System.out.print(".");

            Thread t1 = new Thread(new Runnable() {
                public void run() {
                    try {
                        cb.waitForTurn(2);
                        manager.upgradeLock(owner2, res1);
                        cb.signalTurn(3);
                        cb.waitForTurn(5);
                        synchronized (manager.getLock(res1)) {
                            cb.signalTurn(6);
                            manager.writeLock(owner2, res1);
                        }
                        // we must always be first as we will be preferred over
                        // as I had the upgrade
                        // lock before
                        synchronized (this) {
                            if (first == null)
                                first = owner2;
                        }
                        manager.releaseAll(owner2);
                        synchronized (restart) {
                            restart.meet();
                            restart.reset();
                        }
                    } catch (InterruptedException ie) {
                    }
                }
            }, "Thread #1");

            t1.start();

            Thread t2 = new Thread(new Runnable() {
                public void run() {
                    try {
                        // I wait until the others are blocked
                        // when I release my single read lock, thread #1 always
                        // should be the
                        // next to get the lock as it is preferred over the main
                        // thread
                        // that only waits for a read lock
                        cb.waitForTurn(6);
                        synchronized (manager.getLock(res1)) {
                            cb.signalTurn(7);
                            manager.readLock(owner3, res1);
                        }
                        synchronized (this) {
                            if (first == null)
                                first = owner3;
                        }
                        manager.releaseAll(owner3);
                        synchronized (restart) {
                            restart.meet();
                            restart.reset();
                        }
                    } catch (InterruptedException ie) {
                    }
                }
            }, "Thread #2");

            t2.start();

            cb.waitForTurn(1);
            manager.readLock(owner1, res1);
            cb.signalTurn(2);
            cb.waitForTurn(3);
            manager.release(owner1, res1);
            manager.readLock(owner1, res1);
            cb.signalTurn(5);
            cb.waitForTurn(7);
            synchronized (manager.getLock(res1)) {
                manager.releaseAll(owner1);
            }
            synchronized (restart) {
                restart.meet();
                restart.reset();
            }

            assertEquals(first, owner2);
            first = null;
            cb.reset();
        }

    }
    
    /*
     * 
     * Test shows that two preference locks that are imcompatible do not cause a lock out
     * which was the case with GenericLock 1.5
     * Before the fix this test would dealock
     * 
     *                  Owner           Owner           Owner
     * Step             #1              #2              #3
     * 1                read (ok)
     * 2                                write preferred 
     *                                  (blocked 
     *                                  because of #1)
     * 3                                                write preferred 
     *                                                  (blocked 
     *                                                  because of #1 and #2)
     * 4                release
     * 5                                resumed   or    resumed 
     *                                  (as both are preferred, problem
     *                                   is that that would exclude each other
     *                                   in the algorithm used)
     * 6                                released   or   released
     * 7                                resumed   or    resumed 
     * 8                                released   or   released
     * 
     * 
     */
    public void testPreference() throws Throwable {

        sLogger.logInfo("\n\nChecking incompatible preference locks\n\n");
        
        final String owner1 = "owner1";
        final String owner2 = "owner2";
        final String owner3 = "owner3";

        final String res1 = "res1";

        final ReadWriteLock lock = new ReadWriteLock(res1, sLogger);

        final RendezvousBarrier restart = new RendezvousBarrier("restart", 3, TIMEOUT, sLogger);

        final TurnBarrier cb = new TurnBarrier("cb1", TIMEOUT, sLogger, 1);

        for (int i = 0; i < CONCURRENT_TESTS; i++) {
            
            System.out.print(".");

            Thread t1 = new Thread(new Runnable() {
                public void run() {
                    try {
                        cb.waitForTurn(2);
                        synchronized (lock) {
                            cb.signalTurn(3);
                            lock.acquire(owner2, ReadWriteLock.WRITE_LOCK, true,
                                    GenericLock.COMPATIBILITY_REENTRANT, true, TIMEOUT);
                        }
                        lock.release(owner2);
                        synchronized (restart) {
                            restart.meet();
                            restart.reset();
                        }
                    } catch (InterruptedException ie) {
                    }
                }
            }, "Thread #1");

            t1.start();

            Thread t2 = new Thread(new Runnable() {
                public void run() {
                    try {
                        cb.waitForTurn(3);
                        synchronized (lock) {
                            cb.signalTurn(4);
                            lock.acquire(owner3, ReadWriteLock.WRITE_LOCK, true,
                                    GenericLock.COMPATIBILITY_REENTRANT, true, TIMEOUT);
                        }
                        lock.release(owner3);
                        synchronized (restart) {
                            restart.meet();
                            restart.reset();
                        }
                    } catch (InterruptedException ie) {
                    }
                }
            }, "Thread #2");

            t2.start();

            cb.waitForTurn(1);
            lock.acquireRead(owner1, TIMEOUT);
            cb.signalTurn(2);
            cb.waitForTurn(4);
            synchronized (lock) {
                lock.release(owner1);
            }
            synchronized (restart) {
                restart.meet();
                restart.reset();
            }

            cb.reset();
        }

    }
    
    public void testGlobalTimeout() throws Throwable {

        sLogger.logInfo("\n\nChecking global timeouts\n\n");
        
        final String owner1 = "owner1";
        final String owner2 = "owner2";

        final String res1 = "res1";

        final GenericLockManager manager = new GenericLockManager(1, sLogger, TIMEOUT, -1);

        final RendezvousBarrier restart = new RendezvousBarrier("restart", 2, TIMEOUT, sLogger);

        final TurnBarrier cb = new TurnBarrier("cb1", TIMEOUT, sLogger, 1);

        for (int i = 0; i < CONCURRENT_TESTS; i++) {
            
            System.out.print(".");

            Thread t1 = new Thread(new Runnable() {
                public void run() {
                    try {
                        cb.waitForTurn(2);
                        manager.lock(owner2, res1, 1, true);
                        cb.signalTurn(3);
                        manager.releaseAll(owner2);
                        synchronized (restart) {
                            restart.meet();
                            restart.reset();
                        }
                    } catch (InterruptedException ie) {
                    }
                }
            }, "Thread #1");

            t1.start();

            cb.waitForTurn(1);
            manager.lock(owner1, res1, 1, true);
            manager.setGlobalTimeout(owner1, 500);
            cb.signalTurn(2);
            cb.waitForTurn(3);
            boolean failed = false;
            try {
                manager.tryLock(owner1, res1, 1, true);
            } catch (LockException le) {
                failed = true;
            }
            assertTrue(failed);
            manager.releaseAll(owner1);
            failed = false;
            try {
                manager.tryLock(owner1, res1, 1, true);
            } catch (LockException le) {
                failed = true;
            }
            assertFalse(failed);
            synchronized (restart) {
                restart.meet();
                restart.reset();
            }

            cb.reset();
        }

    }

    public void testStress() throws Throwable {

        sLogger.logInfo("\n\nStress checking locks\n\n");
        
        final String owner1 = "owner1";
        final String owner2 = "owner2";
        final String owner3 = "owner3";
        final String owner4 = "owner4";
        final String owner5 = "owner5";
        final String owner6 = "owner6";
        final String owner7 = "owner7";
        final String owner8 = "owner8";
        final String owner9 = "owner9";
        final String owner10 = "owner10";

        final String res1 = "res1";
        final String res2 = "res2";
        final String res3 = "res3";

        // choose low timeout so sometimes an owner times out
        final ReadWriteUpgradeLockManager manager = new ReadWriteUpgradeLockManager(sLogger, 100);

        final RendezvousBarrier restart = new RendezvousBarrier("restart", 5, TIMEOUT, sLogger);
        final RendezvousBarrier start = new RendezvousBarrier("start", 5, TIMEOUT, sLogger);

        for (int i = 0; i < CONCURRENT_TESTS; i++) {
            
            System.out.print(".");

            Thread t1 = new Thread(new Runnable() {
                public void run() {
                    try {
                        try {
                            synchronized (start) {
                                start.meet();
                                start.reset();
                            }
                            manager.readLock(owner1, res1);
                            manager.readLock(owner1, res2);
                            manager.upgradeLock(owner1, res3);
                            manager.writeLock(owner1, res3);
                        } catch (LockException ie) {
                        } finally {
                            manager.releaseAll(owner1);
                            synchronized (restart) {
                                restart.meet();
                                restart.reset();
                            }
                        }
                    } catch (InterruptedException ie) {
                    }
                }
            }, "Thread #1");
            t1.start();

            Thread t2 = new Thread(new Runnable() {
                public void run() {
                    try {
                        try {
                            synchronized (start) {
                                start.meet();
                                start.reset();
                            }
                            manager.readLock(owner2, res1);
                            manager.readLock(owner2, res2);
                            manager.upgradeLock(owner2, res3);
                            manager.writeLock(owner2, res3);
                        } catch (LockException ie) {
                        } finally {
                            manager.releaseAll(owner2);
                            synchronized (restart) {
                                restart.meet();
                                restart.reset();
                            }
                        }
                    } catch (InterruptedException ie) {
                    }
                }
            }, "Thread #2");
            t2.start();

            Thread t3 = new Thread(new Runnable() {
                public void run() {
                    try {
                        try {
                            synchronized (start) {
                                start.meet();
                                start.reset();
                            }
                            manager.readLock(owner3, res1);
                            manager.readLock(owner3, res2);
                            manager.upgradeLock(owner3, res3);
                            manager.writeLock(owner3, res3);
                        } catch (LockException ie) {
                        } finally {
                            manager.releaseAll(owner3);
                            synchronized (restart) {
                                restart.meet();
                                restart.reset();
                            }
                        }
                    } catch (InterruptedException ie) {
                    }
                }
            }, "Thread #3");
            t3.start();

            Thread t4 = new Thread(new Runnable() {
                public void run() {
                    try {
                        try {
                            synchronized (start) {
                                start.meet();
                                start.reset();
                            }
                            manager.readLock(owner4, res1);
                            manager.readLock(owner4, res2);
                            manager.upgradeLock(owner4, res3);
                            manager.writeLock(owner4, res3);
                        } catch (LockException ie) {
                        } finally {
                            manager.releaseAll(owner4);
                            synchronized (restart) {
                                restart.meet();
                                restart.reset();
                            }
                        }
                    } catch (InterruptedException ie) {
                    }
                }
            }, "Thread #4");
            t4.start();

            try {
                try {
                    synchronized (start) {
                        start.meet();
                        start.reset();
                    }
                    manager.readLock("reader", res1);
                    manager.readLock("reader", res2);
                    manager.readLock("reader", res3);

                } catch (LockException ie) {
                } finally {
                    manager.releaseAll("reader");
                    try {
                        synchronized (restart) {
                            restart.meet();
                            restart.reset();
                        }
                    } catch (InterruptedException ie) {
                    }
                }
            } catch (InterruptedException ie) {
            }
        }

    }
}
