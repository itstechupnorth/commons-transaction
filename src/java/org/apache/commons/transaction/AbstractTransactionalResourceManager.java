/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.transaction;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.commons.transaction.locking.LockException;
import org.apache.commons.transaction.locking.ReadWriteLockManager;
import org.apache.commons.transaction.locking.LockException.Code;

/**
 * Not thread-safe. FIXME: Should it be?
 *  
 * @author olli
 *
 * @param <T>
 */
public abstract class AbstractTransactionalResourceManager<T extends AbstractTransactionalResourceManager.AbstractTxContext> implements TransactionalResourceManager {
    protected ThreadLocal<T> activeTx = new ThreadLocal<T>();

    protected abstract T createContext();

    /**
     * Checks whether this transaction has been marked to allow a rollback as
     * the only valid outcome. This can be set my method
     * {@link #markTransactionForRollback()} or might be set internally be any
     * fatal error. Once a transaction is marked for rollback there is no way to
     * undo this. A transaction that is marked for rollback can not be
     * committed, also rolled back.
     * 
     * @return <code>true</code> if this transaction has been marked for a
     *         roll back
     * @see #markTransactionForRollback()
     */
    public boolean isTransactionMarkedForRollback() {
        T txContext = getActiveTx();

        if (txContext == null) {
            throw new IllegalStateException("Active thread " + Thread.currentThread()
                    + " not associated with a transaction!");
        }

        return (txContext.isMarkedForRollback());
    }

    @Override
    public void startTransaction() {
        if (getActiveTx() != null) {
            throw new IllegalStateException("Active thread " + Thread.currentThread()
                    + " already associated with a transaction!");
        }
        T txContent = createContext();
        setActiveTx(txContent);

    }

    @Override
    public void rollbackTransaction() {
        T txContext = getActiveTx();

        if (txContext == null) {
            throw new IllegalStateException("Active thread " + Thread.currentThread()
                    + " not associated with a transaction!");
        }

        txContext.dispose();
        setActiveTx(null);
    }

    @Override
    public void commitTransaction() {
        T txContext = getActiveTx();

        if (txContext == null) {
            throw new IllegalStateException("Active thread " + Thread.currentThread()
                    + " not associated with a transaction!");
        }

        if (txContext.isMarkedForRollback()) {
            throw new IllegalStateException("Active thread " + Thread.currentThread()
                    + " is marked for rollback!");
        }

        txContext.commit();
        txContext.dispose();
        setActiveTx(null);
    }

    /**
     * Prepares the changes done inside this transaction reasource.
     *  
     */
    public boolean prepareTransaction() {
        T txContext = getActiveTx();

        if (txContext == null) {
            throw new IllegalStateException("Active thread " + Thread.currentThread()
                    + " not associated with a transaction!");
        }
        return txContext.prepare();
    }

    @Override
    public void setTransactionTimeout(long mSecs) {
        T txContext = getActiveTx();

        if (txContext == null) {
            throw new IllegalStateException("Active thread " + Thread.currentThread()
                    + " not associated with a transaction!");
        }
        
        txContext.setTimeout(mSecs);
    }

    protected T getActiveTx() {
        return activeTx.get();
    }

    protected void setActiveTx(T txContext) {
        activeTx.set(txContext);
    }

    public boolean isTransactionPrepared() {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean isReadOnlyTransaction() {
        // TODO Auto-generated method stub
        return false;
    }

    /**
     * Marks the current transaction to allow only a rollback as valid outcome.
     * 
     * @see #isTransactionMarkedForRollback()
     */
    public void markTransactionForRollback() {
        // TODO Auto-generated method stub
        
    }

    public abstract class AbstractTxContext {
        private long timeout = -1L;

        private long startTime = -1L;

        private boolean readOnly = true;
        
        private boolean prepared = false;
        private boolean markedForRollback = false;
        
        private ReadWriteLockManager lm = new ReadWriteLockManager();

        public AbstractTxContext() {
            startTime = System.currentTimeMillis();
        }

        protected long getRemainingTimeout() {
            long now = System.currentTimeMillis();
            return (getStartTime()- now + timeout);
        }

        public void dispose() {
            Iterable<ReadWriteLock> locks = getLm().getAllForCurrentThread();

            for (ReadWriteLock lock : locks) {
                lock.readLock().unlock();
                lock.writeLock().unlock();
            }
        }

        public boolean isReadOnly() {
            return readOnly;
        }

        public boolean prepare() {
            prepared = true;
            return true;
        }

        public long getStartTime() {
            return startTime;
        }

        public void setStartTime(long startTimeMSecs) {
            this.startTime = startTimeMSecs;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeoutMSecs) {
            this.timeout = timeoutMSecs;
        }
        
        public void setReadOnly(boolean readOnly) {
            this.readOnly = readOnly;
        }

        public ReadWriteLockManager getLm() {
            return lm;
        }

        protected ReadWriteLock initLock(Object id) {
            return getLm().putIfAbsent(id, getLm().create());
        }

        public void readLock(Object id) throws LockException {
            try {
                boolean locked = initLock(id).readLock().tryLock(getRemainingTimeout(), TimeUnit.MILLISECONDS);
                if (!locked) {
                    throw new LockException(Code.TIMED_OUT, id);
                }
            } catch (InterruptedException e) {
                throw new LockException(Code.INTERRUPTED, id);
            }
        }

        public void writeLock(Object id)throws LockException  {
            try {
                boolean locked = initLock(id).writeLock().tryLock(getRemainingTimeout(), TimeUnit.MILLISECONDS);
                if (!locked) {
                    throw new LockException(Code.TIMED_OUT, id);
                }
            } catch (InterruptedException e) {
                throw new LockException(Code.INTERRUPTED, id);
            }
        }

        public boolean isMarkedForRollback() {
            return markedForRollback;
        }

        public boolean isPrepared() {
            return prepared;
        }

        public void markForRollback() {
            markedForRollback = true;
        }

        public void commit() {
            
        }
    }

}