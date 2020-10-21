package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.LockSupport;

/**
 * {@link "https://juejin.im/post/6844903865154797581#heading-0"}
 * {@link "https://mp.weixin.qq.com/s/JkBPtojeNF-EwsQXiuYqng"}
 *
 * StampedLock在读线程非常多而写线程非常少的场景下非常适用，同时还避免了写饥饿情况的发生。并且StampedLock不支持可重入，并且不支持Condition.
 *
 * @author Doug Lea
 * @since 1.8
 */
public class StampedLock implements java.io.Serializable {
    private static final long serialVersionUID = -6001602636862214147L;

    private static final int NCPU = Runtime.getRuntime().availableProcessors(); //处理器数量，用于自旋控制.
    private static final int SPINS = (NCPU > 1) ? 1 << 6 : 0;  //在当前节点加入队列前，如果队列头尾节点相等，即属性whead和wtail相等，先让其自旋一定的大小，自旋的值.
    private static final int HEAD_SPINS = (NCPU > 1) ? 1 << 10 : 0;  //在阻塞当前线程前，如果队列头尾节点相等，即属性whead和wtail相等，先让其自旋一定的大小，自旋的值.
    private static final int MAX_HEAD_SPINS = (NCPU > 1) ? 1 << 16 : 0;  //重新阻塞之前的最大重试次数
    private static final int OVERFLOW_YIELD_RATE = 7; //读锁大小溢出时，超过126，线程自增的随机数&上OVERFLOW_YIELD_RATE时会yeild（一定是2的幂减1）
    private static final int LG_READERS = 7; //读锁最大的bit位
    private static final long RUNIT = 1L;  //获取悲观读成功时，state增加的值
    private static final long WBIT = 1L << LG_READERS;  //写锁获取成功，state增加的值，写锁标志位（10000000）128
    private static final long RBITS = WBIT - 1L; //在获取当前读锁的个数，判断当前stampedLock是属于读锁的状态，127
    private static final long RFULL = RBITS - 1L;  //最大的读锁大小，126
    private static final long ABITS = RBITS | WBIT; //包含读锁标志位和写锁标志位和起来，在获取锁和释放锁中使用，比如用于判断state是否处于读锁还是写锁，还是无锁状态
    private static final long SBITS = ~RBITS; //用于在乐观锁和释放锁使用
    private static final long ORIGIN = WBIT << 1; //StampedLock初始化,state的初始值100000000，256
    private static final long INTERRUPTED = 1L; //如果当前线程被中断，获取读写锁时，返回的值

    private static final int WAITING = -1; //节点的等待状态
    private static final int CANCELLED = 1; //节点的取消状态
    private static final int RMODE = 0; //节点属于读模式
    private static final int WMODE = 1; //节点属于写模式

    //等待节点类
    static final class WNode {
        volatile WNode prev;      //前驱节点
        volatile WNode next;      //后继节点
        volatile WNode cowait;    //等待的读模式节点
        volatile Thread thread;   //节点对应的线程
        volatile int status;      //节点的状态
        final int mode;           //当前节点是处于读锁模式还是写锁模式

        //构造函数，传入读写锁模式和前驱节点
        WNode(int m, WNode p) {
            mode = m;
            prev = p;
        }
    }

    private transient volatile WNode whead;   //stampedLock队列中的头节点
    private transient volatile WNode wtail;   //stampedLock队列中的尾节点

    transient ReadLockView readLockView;           //读锁的视图，不可重入，并且不支持condition
    transient WriteLockView writeLockView;         //写锁的视图，不可重入并且不支持condition
    transient ReadWriteLockView readWriteLockView; //读写锁的视图

    private transient volatile long state;  //stampedLock的状态，用于判断当前stampedLock是属于读锁还是写锁还是乐观锁
    private transient int readerOverflow;   //读锁溢出时，记录额外的读锁大小

    //state被初始化为256，写锁标志位128，读锁位
    public StampedLock() {
        state = ORIGIN;
    }

    //获取写锁，如果不能马上获取到，必要的时候会将其阻塞，writeLock方法不支持中断操作
    public long writeLock() {
        long s, next;
        //如果当前StampedLock的状态state为初始状态即256（100000000）& 255等于0，表明当前属于无锁状态，
        //写锁可以获取成功，由于是对共享变量的操作，使用cas，进行变量的更新。否则&255不等于0表明当前处于有锁状态，调用acquireWrite方法
        return ((((s = state) & ABITS) == 0L &&
                U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ?
                next : acquireWrite(false, 0L));
    }


    //非阻塞的获取写锁，如果获取写锁失败返回stamp为0，如果当前处于无锁状态并且cas更新StampedLock的state属性成功，返回s+WBIT的stamp
    public long tryWriteLock() {
        long s, next;
        return ((((s = state) & ABITS) == 0L &&
                U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ?
                next : 0L);
    }

    //超时的获取写锁，并且支持中断操作
    public long tryWriteLock(long time, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(time);  //将其时间转成纳秒
        if (!Thread.interrupted()) { //线程没有被打断
            long next, deadline;
            if ((next = tryWriteLock()) != 0L) //非阻塞tryWriteLock获取写锁，如果能加锁成功直接返回
                return next;
            if (nanos <= 0L) //如果时间小于等于0直接返回，加写锁失败
                return 0L;
            //System.nanoTime（）可能返回负，如果和传入的时间相加等于0，deadline等于1
            if ((deadline = System.nanoTime() + nanos) == 0L)
                deadline = 1L;
            //调用acquireWrite方法，如果超时，返回的结果不是中断的值INTERRUPTED,加锁成功，返回对应的Stamp值（state+WBIT）
            if ((next = acquireWrite(true, deadline)) != INTERRUPTED)
                return next;
        }
        //否则抛出中断异常
        throw new InterruptedException();
    }

    //中断的获取写锁，获取不到写锁抛出中断异常
    public long writeLockInterruptibly() throws InterruptedException {
        long next;
        //当前线程没有被中断，并且调用acquireWrite方法不是返回INTERRUPTED中断标志位，否则抛出中断异常，如果返回的标志位是0，也表示获取写锁失败
        if (!Thread.interrupted() &&
                (next = acquireWrite(true, 0L)) != INTERRUPTED)
            return next;
        //抛出中断异常
        throw new InterruptedException();
    }

    /**
     * Non-exclusively acquires the lock, blocking if necessary
     * until available.
     *
     * @return a stamp that can be used to unlock or convert mode
     */
    public long readLock() {
        long s = state, next;  // bypass acquireRead on common uncontended case
        return ((whead == wtail && (s & ABITS) < RFULL &&
                U.compareAndSwapLong(this, STATE, s, next = s + RUNIT)) ?
                next : acquireRead(false, 0L));
    }

    /**
     * Non-exclusively acquires the lock if it is immediately available.
     *
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     */
    public long tryReadLock() {
        for (; ; ) {
            long s, m, next;
            if ((m = (s = state) & ABITS) == WBIT)
                return 0L;
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                    return next;
            } else if ((next = tryIncReaderOverflow(s)) != 0L)
                return next;
        }
    }

    /**
     * Non-exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long, TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     *                              before acquiring the lock
     */
    public long tryReadLock(long time, TimeUnit unit)
            throws InterruptedException {
        long s, m, next, deadline;
        long nanos = unit.toNanos(time);
        if (!Thread.interrupted()) {
            if ((m = (s = state) & ABITS) != WBIT) {
                if (m < RFULL) {
                    if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                        return next;
                } else if ((next = tryIncReaderOverflow(s)) != 0L)
                    return next;
            }
            if (nanos <= 0L)
                return 0L;
            if ((deadline = System.nanoTime() + nanos) == 0L)
                deadline = 1L;
            if ((next = acquireRead(true, deadline)) != INTERRUPTED)
                return next;
        }
        throw new InterruptedException();
    }

    /**
     * Non-exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     *
     * @return a stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     *                              before acquiring the lock
     */
    public long readLockInterruptibly() throws InterruptedException {
        long next;
        if (!Thread.interrupted() &&
                (next = acquireRead(true, 0L)) != INTERRUPTED)
            return next;
        throw new InterruptedException();
    }

    /**
     * Returns a stamp that can later be validated, or zero
     * if exclusively locked.
     *
     * @return a stamp, or zero if exclusively locked
     */
    public long tryOptimisticRead() {
        long s;
        return (((s = state) & WBIT) == 0L) ? (s & SBITS) : 0L;
    }

    /**
     * Returns true if the lock has not been exclusively acquired
     * since issuance of the given stamp. Always returns false if the
     * stamp is zero. Always returns true if the stamp represents a
     * currently held lock. Invoking this method with a value not
     * obtained from {@link #tryOptimisticRead} or a locking method
     * for this lock has no defined effect or result.
     *
     * @param stamp a stamp
     * @return {@code true} if the lock has not been exclusively acquired
     * since issuance of the given stamp; else false
     */
    public boolean validate(long stamp) {
        U.loadFence();
        return (stamp & SBITS) == (state & SBITS);
    }

    /**
     * If the lock state matches the given stamp, releases the
     * exclusive lock.
     *
     * @param stamp a stamp returned by a write-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     *                                      not match the current state of this lock
     */
    public void unlockWrite(long stamp) {
        WNode h;
        if (state != stamp || (stamp & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        state = (stamp += WBIT) == 0L ? ORIGIN : stamp;
        if ((h = whead) != null && h.status != 0)
            release(h);
    }

    /**
     * If the lock state matches the given stamp, releases the
     * non-exclusive lock.
     *
     * @param stamp a stamp returned by a read-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     *                                      not match the current state of this lock
     */
    public void unlockRead(long stamp) {
        long s, m;
        WNode h;
        for (; ; ) {
            if (((s = state) & SBITS) != (stamp & SBITS) ||
                    (stamp & ABITS) == 0L || (m = s & ABITS) == 0L || m == WBIT)
                throw new IllegalMonitorStateException();
            if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    break;
                }
            } else if (tryDecReaderOverflow(s) != 0L)
                break;
        }
    }

    /**
     * If the lock state matches the given stamp, releases the
     * corresponding mode of the lock.
     *
     * @param stamp a stamp returned by a lock operation
     * @throws IllegalMonitorStateException if the stamp does
     *                                      not match the current state of this lock
     */
    public void unlock(long stamp) {
        long a = stamp & ABITS, m, s;
        WNode h;
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((m = s & ABITS) == 0L)
                break;
            else if (m == WBIT) {
                if (a != m)
                    break;
                state = (s += WBIT) == 0L ? ORIGIN : s;
                if ((h = whead) != null && h.status != 0)
                    release(h);
                return;
            } else if (a == 0L || a >= WBIT)
                break;
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return;
                }
            } else if (tryDecReaderOverflow(s) != 0L)
                return;
        }
        throw new IllegalMonitorStateException();
    }

    /**
     * If the lock state matches the given stamp, performs one of
     * the following actions. If the stamp represents holding a write
     * lock, returns it.  Or, if a read lock, if the write lock is
     * available, releases the read lock and returns a write stamp.
     * Or, if an optimistic read, returns a write stamp only if
     * immediately available. This method returns zero in all other
     * cases.
     *
     * @param stamp a stamp
     * @return a valid write stamp, or zero on failure
     */
    public long tryConvertToWriteLock(long stamp) {
        long a = stamp & ABITS, m, s, next;
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((m = s & ABITS) == 0L) {
                if (a != 0L)
                    break;
                if (U.compareAndSwapLong(this, STATE, s, next = s + WBIT))
                    return next;
            } else if (m == WBIT) {
                if (a != m)
                    break;
                return stamp;
            } else if (m == RUNIT && a != 0L) {
                if (U.compareAndSwapLong(this, STATE, s,
                        next = s - RUNIT + WBIT))
                    return next;
            } else
                break;
        }
        return 0L;
    }

    /**
     * If the lock state matches the given stamp, performs one of
     * the following actions. If the stamp represents holding a write
     * lock, releases it and obtains a read lock.  Or, if a read lock,
     * returns it. Or, if an optimistic read, acquires a read lock and
     * returns a read stamp only if immediately available. This method
     * returns zero in all other cases.
     *
     * @param stamp a stamp
     * @return a valid read stamp, or zero on failure
     */
    public long tryConvertToReadLock(long stamp) {
        long a = stamp & ABITS, m, s, next;
        WNode h;
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((m = s & ABITS) == 0L) {
                if (a != 0L)
                    break;
                else if (m < RFULL) {
                    if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                        return next;
                } else if ((next = tryIncReaderOverflow(s)) != 0L)
                    return next;
            } else if (m == WBIT) {
                if (a != m)
                    break;
                state = next = s + (WBIT + RUNIT);
                if ((h = whead) != null && h.status != 0)
                    release(h);
                return next;
            } else if (a != 0L && a < WBIT)
                return stamp;
            else
                break;
        }
        return 0L;
    }

    /**
     * If the lock state matches the given stamp then, if the stamp
     * represents holding a lock, releases it and returns an
     * observation stamp.  Or, if an optimistic read, returns it if
     * validated. This method returns zero in all other cases, and so
     * may be useful as a form of "tryUnlock".
     *
     * @param stamp a stamp
     * @return a valid optimistic read stamp, or zero on failure
     */
    public long tryConvertToOptimisticRead(long stamp) {
        long a = stamp & ABITS, m, s, next;
        WNode h;
        U.loadFence();
        for (; ; ) {
            if (((s = state) & SBITS) != (stamp & SBITS))
                break;
            if ((m = s & ABITS) == 0L) {
                if (a != 0L)
                    break;
                return s;
            } else if (m == WBIT) {
                if (a != m)
                    break;
                state = next = (s += WBIT) == 0L ? ORIGIN : s;
                if ((h = whead) != null && h.status != 0)
                    release(h);
                return next;
            } else if (a == 0L || a >= WBIT)
                break;
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, next = s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return next & SBITS;
                }
            } else if ((next = tryDecReaderOverflow(s)) != 0L)
                return next & SBITS;
        }
        return 0L;
    }

    /**
     * Releases the write lock if it is held, without requiring a
     * stamp value. This method may be useful for recovery after
     * errors.
     *
     * @return {@code true} if the lock was held, else false
     */
    public boolean tryUnlockWrite() {
        long s;
        WNode h;
        if (((s = state) & WBIT) != 0L) {
            state = (s += WBIT) == 0L ? ORIGIN : s;
            if ((h = whead) != null && h.status != 0)
                release(h);
            return true;
        }
        return false;
    }

    /**
     * Releases one hold of the read lock if it is held, without
     * requiring a stamp value. This method may be useful for recovery
     * after errors.
     *
     * @return {@code true} if the read lock was held, else false
     */
    public boolean tryUnlockRead() {
        long s, m;
        WNode h;
        while ((m = (s = state) & ABITS) != 0L && m < WBIT) {
            if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return true;
                }
            } else if (tryDecReaderOverflow(s) != 0L)
                return true;
        }
        return false;
    }

    // status monitoring methods

    /**
     * Returns combined state-held and overflow read count for given
     * state s.
     */
    private int getReadLockCount(long s) {
        long readers;
        if ((readers = s & RBITS) >= RFULL)
            readers = RFULL + readerOverflow;
        return (int) readers;
    }

    /**
     * Returns {@code true} if the lock is currently held exclusively.
     *
     * @return {@code true} if the lock is currently held exclusively
     */
    public boolean isWriteLocked() {
        return (state & WBIT) != 0L;
    }

    /**
     * Returns {@code true} if the lock is currently held non-exclusively.
     *
     * @return {@code true} if the lock is currently held non-exclusively
     */
    public boolean isReadLocked() {
        return (state & RBITS) != 0L;
    }

    /**
     * Queries the number of read locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     *
     * @return the number of read locks held
     */
    public int getReadLockCount() {
        return getReadLockCount(state);
    }

    /**
     * Returns a string identifying this lock, as well as its lock
     * state.  The state, in brackets, includes the String {@code
     * "Unlocked"} or the String {@code "Write-locked"} or the String
     * {@code "Read-locks:"} followed by the current number of
     * read-locks held.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        long s = state;
        return super.toString() +
                ((s & ABITS) == 0L ? "[Unlocked]" :
                        (s & WBIT) != 0L ? "[Write-locked]" :
                                "[Read-locks:" + getReadLockCount(s) + "]");
    }

    // views

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #readLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link
     * Lock#newCondition()} throws {@code
     * UnsupportedOperationException}.
     *
     * @return the lock
     */
    public Lock asReadLock() {
        ReadLockView v;
        return ((v = readLockView) != null ? v :
                (readLockView = new ReadLockView()));
    }

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #writeLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link
     * Lock#newCondition()} throws {@code
     * UnsupportedOperationException}.
     *
     * @return the lock
     */
    public Lock asWriteLock() {
        WriteLockView v;
        return ((v = writeLockView) != null ? v :
                (writeLockView = new WriteLockView()));
    }

    /**
     * Returns a {@link ReadWriteLock} view of this StampedLock in
     * which the {@link ReadWriteLock#readLock()} method is mapped to
     * {@link #asReadLock()}, and {@link ReadWriteLock#writeLock()} to
     * {@link #asWriteLock()}.
     *
     * @return the lock
     */
    public ReadWriteLock asReadWriteLock() {
        ReadWriteLockView v;
        return ((v = readWriteLockView) != null ? v :
                (readWriteLockView = new ReadWriteLockView()));
    }

    //读锁视图
    final class ReadLockView implements Lock {
        public void lock() {
            readLock();
        }

        public void lockInterruptibly() throws InterruptedException {
            readLockInterruptibly();
        }

        public boolean tryLock() {
            return tryReadLock() != 0L;
        }

        public boolean tryLock(long time, TimeUnit unit)
                throws InterruptedException {
            return tryReadLock(time, unit) != 0L;
        }

        public void unlock() {
            unstampedUnlockRead();
        }

        //不支持Condition，和ReadWriteLock的区别
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    //写锁视图
    final class WriteLockView implements Lock {
        public void lock() {
            writeLock();
        }

        public void lockInterruptibly() throws InterruptedException {
            writeLockInterruptibly();
        }

        public boolean tryLock() {
            return tryWriteLock() != 0L;
        }

        public boolean tryLock(long time, TimeUnit unit)
                throws InterruptedException {
            return tryWriteLock(time, unit) != 0L;
        }

        public void unlock() {
            unstampedUnlockWrite();
        }

        //不支持Condition，和ReadWriteLock的区别
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    //读写锁视图
    final class ReadWriteLockView implements ReadWriteLock {
        //获取读锁视图，如果读锁视图未初始化，初始化读锁视图
        public Lock readLock() {
            return asReadLock();
        }

        //获取写锁视图，如果写锁视图未初始化，初始化写锁视图
        public Lock writeLock() {
            return asWriteLock();
        }
    }

    // Unlock methods without stamp argument checks for view classes.
    // Needed because view-class lock methods throw away stamps.

    final void unstampedUnlockWrite() {
        WNode h;
        long s;
        if (((s = state) & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        state = (s += WBIT) == 0L ? ORIGIN : s;
        if ((h = whead) != null && h.status != 0)
            release(h);
    }

    final void unstampedUnlockRead() {
        for (; ; ) {
            long s, m;
            WNode h;
            if ((m = (s = state) & ABITS) == 0L || m >= WBIT)
                throw new IllegalMonitorStateException();
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    break;
                }
            } else if (tryDecReaderOverflow(s) != 0L)
                break;
        }
    }

    private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        state = ORIGIN; // reset to unlocked state
    }

    // internals

    /**
     * Tries to increment readerOverflow by first setting state
     * access bits value to RBITS, indicating hold of spinlock,
     * then updating, then releasing.
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryIncReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        if ((s & ABITS) == RFULL) {
            if (U.compareAndSwapLong(this, STATE, s, s | RBITS)) {
                ++readerOverflow;
                state = s;
                return s;
            }
        } else if ((LockSupport.nextSecondarySeed() &
                OVERFLOW_YIELD_RATE) == 0)
            Thread.yield();
        return 0L;
    }

    /**
     * Tries to decrement readerOverflow.
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryDecReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        if ((s & ABITS) == RFULL) {
            if (U.compareAndSwapLong(this, STATE, s, s | RBITS)) {
                int r;
                long next;
                if ((r = readerOverflow) > 0) {
                    readerOverflow = r - 1;
                    next = s;
                } else
                    next = s - RUNIT;
                state = next;
                return next;
            }
        } else if ((LockSupport.nextSecondarySeed() &
                OVERFLOW_YIELD_RATE) == 0)
            Thread.yield();
        return 0L;
    }

    /**
     * Wakes up the successor of h (normally whead). This is normally
     * just h.next, but may require traversal from wtail if next
     * pointers are lagging. This may fail to wake up an acquiring
     * thread when one or more have been cancelled, but the cancel
     * methods themselves provide extra safeguards to ensure liveness.
     */
    private void release(WNode h) {
        if (h != null) {
            WNode q;
            Thread w;
            U.compareAndSwapInt(h, WSTATUS, WAITING, 0);
            if ((q = h.next) == null || q.status == CANCELLED) {
                for (WNode t = wtail; t != null && t != h; t = t.prev)
                    if (t.status <= 0)
                        q = t;
            }
            if (q != null && (w = q.thread) != null)
                U.unpark(w);
        }
    }

    private long acquireWrite(boolean interruptible, long deadline) {
        WNode node = null, p;
        //将其当前节点，作为队列的尾节点，如果当前队列头结点和尾节点相等，并且StampedLock的状态属性state为WBIT,
        //先自旋一段时间，如果自旋还是没有获取写锁成功，再将其作为队列的尾节点加入
        for (int spins = -1; ; ) {
            long m, s, ns;
            //如果当前state等于256，属于无锁状态，直接加写锁，如果加锁成功直接返回
            if ((m = (s = state) & ABITS) == 0L) {
                if (U.compareAndSwapLong(this, STATE, s, ns = s + WBIT))
                    return ns;
            } else if (spins < 0)
                //如果spins小于0，并且当前的StampedLock属于写锁状态，以及头尾节点相等，spins赋值SPINS,让其当前线程自旋一段时间获取写锁
                spins = (m == WBIT && wtail == whead) ? SPINS : 0;
            else if (spins > 0) {
                //LockSupport.nextSecondarySeed() >= 0永真
                if (LockSupport.nextSecondarySeed() >= 0)
                    --spins;
            } else if ((p = wtail) == null) {  //如果当前队列为空队列，即尾节点为空，初始化队列
                WNode hd = new WNode(WMODE, null); //构造写模式的头节点
                if (U.compareAndSwapObject(this, WHEAD, null, hd)) //如果当前头结点设置成功，将其尾节点设置为头结点
                    wtail = hd;
            } else if (node == null) //如果当前节点为空，初始化当前节点，前驱节点为上一次的尾节点
                node = new WNode(WMODE, p);
            else if (node.prev != p)  //如果尾节点已经改变，重新设置当前节点的前驱节点
                node.prev = p;
            else if (U.compareAndSwapObject(this, WTAIL, p, node)) { //将其当前节点设置为尾节点，如果写锁获取成功，直接退出
                p.next = node;
                break;
            }
        }

        for (int spins = -1; ; ) {
            WNode h, np, pp;
            int ps;
            if ((h = whead) == p) {
                if (spins < 0)
                    spins = HEAD_SPINS;
                else if (spins < MAX_HEAD_SPINS)
                    spins <<= 1;
                for (int k = spins; ; ) { // spin at head
                    long s, ns;
                    if (((s = state) & ABITS) == 0L) {
                        if (U.compareAndSwapLong(this, STATE, s,
                                ns = s + WBIT)) {
                            whead = node;
                            node.prev = null;
                            return ns;
                        }
                    } else if (LockSupport.nextSecondarySeed() >= 0 &&
                            --k <= 0)
                        break;
                }
            } else if (h != null) { // help release stale waiters
                WNode c;
                Thread w;
                while ((c = h.cowait) != null) {
                    if (U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                            (w = c.thread) != null)
                        U.unpark(w);
                }
            }
            if (whead == h) {
                if ((np = node.prev) != p) {
                    if (np != null)
                        (p = np).next = node;   // stale
                } else if ((ps = p.status) == 0)
                    U.compareAndSwapInt(p, WSTATUS, 0, WAITING);
                else if (ps == CANCELLED) {
                    if ((pp = p.prev) != null) {
                        node.prev = pp;
                        pp.next = node;
                    }
                } else {
                    long time; // 0 argument to park means no timeout
                    if (deadline == 0L)
                        time = 0L;
                    else if ((time = deadline - System.nanoTime()) <= 0L)
                        return cancelWaiter(node, node, false);
                    Thread wt = Thread.currentThread();
                    U.putObject(wt, PARKBLOCKER, this);
                    node.thread = wt;
                    if (p.status < 0 && (p != h || (state & ABITS) != 0L) &&
                            whead == h && node.prev == p)
                        U.park(false, time);  // emulate LockSupport.park
                    node.thread = null;
                    U.putObject(wt, PARKBLOCKER, null);
                    if (interruptible && Thread.interrupted())
                        return cancelWaiter(node, node, true);
                }
            }
        }
    }

    /**
     * See above for explanation.
     *
     * @param interruptible true if should check interrupts and if so
     *                      return INTERRUPTED
     * @param deadline      if nonzero, the System.nanoTime value to timeout
     *                      at (and return zero)
     * @return next state, or INTERRUPTED
     */
    private long acquireRead(boolean interruptible, long deadline) {
        WNode node = null, p;
        for (int spins = -1; ; ) {
            WNode h;
            if ((h = whead) == (p = wtail)) {
                for (long m, s, ns; ; ) {
                    if ((m = (s = state) & ABITS) < RFULL ?
                            U.compareAndSwapLong(this, STATE, s, ns = s + RUNIT) :
                            (m < WBIT && (ns = tryIncReaderOverflow(s)) != 0L))
                        return ns;
                    else if (m >= WBIT) {
                        if (spins > 0) {
                            if (LockSupport.nextSecondarySeed() >= 0)
                                --spins;
                        } else {
                            if (spins == 0) {
                                WNode nh = whead, np = wtail;
                                if ((nh == h && np == p) || (h = nh) != (p = np))
                                    break;
                            }
                            spins = SPINS;
                        }
                    }
                }
            }
            if (p == null) { // initialize queue
                WNode hd = new WNode(WMODE, null);
                if (U.compareAndSwapObject(this, WHEAD, null, hd))
                    wtail = hd;
            } else if (node == null)
                node = new WNode(RMODE, p);
            else if (h == p || p.mode != RMODE) {
                if (node.prev != p)
                    node.prev = p;
                else if (U.compareAndSwapObject(this, WTAIL, p, node)) {
                    p.next = node;
                    break;
                }
            } else if (!U.compareAndSwapObject(p, WCOWAIT,
                    node.cowait = p.cowait, node))
                node.cowait = null;
            else {
                for (; ; ) {
                    WNode pp, c;
                    Thread w;
                    if ((h = whead) != null && (c = h.cowait) != null &&
                            U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                            (w = c.thread) != null) // help release
                        U.unpark(w);
                    if (h == (pp = p.prev) || h == p || pp == null) {
                        long m, s, ns;
                        do {
                            if ((m = (s = state) & ABITS) < RFULL ?
                                    U.compareAndSwapLong(this, STATE, s,
                                            ns = s + RUNIT) :
                                    (m < WBIT &&
                                            (ns = tryIncReaderOverflow(s)) != 0L))
                                return ns;
                        } while (m < WBIT);
                    }
                    if (whead == h && p.prev == pp) {
                        long time;
                        if (pp == null || h == p || p.status > 0) {
                            node = null; // throw away
                            break;
                        }
                        if (deadline == 0L)
                            time = 0L;
                        else if ((time = deadline - System.nanoTime()) <= 0L)
                            return cancelWaiter(node, p, false);
                        Thread wt = Thread.currentThread();
                        U.putObject(wt, PARKBLOCKER, this);
                        node.thread = wt;
                        if ((h != pp || (state & ABITS) == WBIT) &&
                                whead == h && p.prev == pp)
                            U.park(false, time);
                        node.thread = null;
                        U.putObject(wt, PARKBLOCKER, null);
                        if (interruptible && Thread.interrupted())
                            return cancelWaiter(node, p, true);
                    }
                }
            }
        }

        for (int spins = -1; ; ) {
            WNode h, np, pp;
            int ps;
            if ((h = whead) == p) {
                if (spins < 0)
                    spins = HEAD_SPINS;
                else if (spins < MAX_HEAD_SPINS)
                    spins <<= 1;
                for (int k = spins; ; ) { // spin at head
                    long m, s, ns;
                    if ((m = (s = state) & ABITS) < RFULL ?
                            U.compareAndSwapLong(this, STATE, s, ns = s + RUNIT) :
                            (m < WBIT && (ns = tryIncReaderOverflow(s)) != 0L)) {
                        WNode c;
                        Thread w;
                        whead = node;
                        node.prev = null;
                        while ((c = node.cowait) != null) {
                            if (U.compareAndSwapObject(node, WCOWAIT,
                                    c, c.cowait) &&
                                    (w = c.thread) != null)
                                U.unpark(w);
                        }
                        return ns;
                    } else if (m >= WBIT &&
                            LockSupport.nextSecondarySeed() >= 0 && --k <= 0)
                        break;
                }
            } else if (h != null) {
                WNode c;
                Thread w;
                while ((c = h.cowait) != null) {
                    if (U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                            (w = c.thread) != null)
                        U.unpark(w);
                }
            }
            if (whead == h) {
                if ((np = node.prev) != p) {
                    if (np != null)
                        (p = np).next = node;   // stale
                } else if ((ps = p.status) == 0)
                    U.compareAndSwapInt(p, WSTATUS, 0, WAITING);
                else if (ps == CANCELLED) {
                    if ((pp = p.prev) != null) {
                        node.prev = pp;
                        pp.next = node;
                    }
                } else {
                    long time;
                    if (deadline == 0L)
                        time = 0L;
                    else if ((time = deadline - System.nanoTime()) <= 0L)
                        return cancelWaiter(node, node, false);
                    Thread wt = Thread.currentThread();
                    U.putObject(wt, PARKBLOCKER, this);
                    node.thread = wt;
                    if (p.status < 0 &&
                            (p != h || (state & ABITS) == WBIT) &&
                            whead == h && node.prev == p)
                        U.park(false, time);
                    node.thread = null;
                    U.putObject(wt, PARKBLOCKER, null);
                    if (interruptible && Thread.interrupted())
                        return cancelWaiter(node, node, true);
                }
            }
        }
    }

    /**
     * If node non-null, forces cancel status and unsplices it from
     * queue if possible and wakes up any cowaiters (of the node, or
     * group, as applicable), and in any case helps release current
     * first waiter if lock is free. (Calling with null arguments
     * serves as a conditional form of release, which is not currently
     * needed but may be needed under possible future cancellation
     * policies). This is a variant of cancellation methods in
     * AbstractQueuedSynchronizer (see its detailed explanation in AQS
     * internal documentation).
     *
     * @param node        if nonnull, the waiter
     * @param group       either node or the group node is cowaiting with
     * @param interrupted if already interrupted
     * @return INTERRUPTED if interrupted or Thread.interrupted, else zero
     */
    private long cancelWaiter(WNode node, WNode group, boolean interrupted) {
        if (node != null && group != null) {
            Thread w;
            node.status = CANCELLED;
            // unsplice cancelled nodes from group
            for (WNode p = group, q; (q = p.cowait) != null; ) {
                if (q.status == CANCELLED) {
                    U.compareAndSwapObject(p, WCOWAIT, q, q.cowait);
                    p = group; // restart
                } else
                    p = q;
            }
            if (group == node) {
                for (WNode r = group.cowait; r != null; r = r.cowait) {
                    if ((w = r.thread) != null)
                        U.unpark(w);       // wake up uncancelled co-waiters
                }
                for (WNode pred = node.prev; pred != null; ) { // unsplice
                    WNode succ, pp;        // find valid successor
                    while ((succ = node.next) == null ||
                            succ.status == CANCELLED) {
                        WNode q = null;    // find successor the slow way
                        for (WNode t = wtail; t != null && t != node; t = t.prev)
                            if (t.status != CANCELLED)
                                q = t;     // don't link if succ cancelled
                        if (succ == q ||   // ensure accurate successor
                                U.compareAndSwapObject(node, WNEXT,
                                        succ, succ = q)) {
                            if (succ == null && node == wtail)
                                U.compareAndSwapObject(this, WTAIL, node, pred);
                            break;
                        }
                    }
                    if (pred.next == node) // unsplice pred link
                        U.compareAndSwapObject(pred, WNEXT, node, succ);
                    if (succ != null && (w = succ.thread) != null) {
                        succ.thread = null;
                        U.unpark(w);       // wake up succ to observe new pred
                    }
                    if (pred.status != CANCELLED || (pp = pred.prev) == null)
                        break;
                    node.prev = pp;        // repeat if new pred wrong/cancelled
                    U.compareAndSwapObject(pp, WNEXT, pred, succ);
                    pred = pp;
                }
            }
        }
        WNode h; // Possibly release first waiter
        while ((h = whead) != null) {
            long s;
            WNode q; // similar to release() but check eligibility
            if ((q = h.next) == null || q.status == CANCELLED) {
                for (WNode t = wtail; t != null && t != h; t = t.prev)
                    if (t.status <= 0)
                        q = t;
            }
            if (h == whead) {
                if (q != null && h.status == 0 &&
                        ((s = state) & ABITS) != WBIT && // waiter is eligible
                        (s == 0L || q.mode == RMODE))
                    release(h);
                break;
            }
        }
        return (interrupted || Thread.interrupted()) ? INTERRUPTED : 0L;
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final long STATE;
    private static final long WHEAD;
    private static final long WTAIL;
    private static final long WNEXT;
    private static final long WSTATUS;
    private static final long WCOWAIT;
    private static final long PARKBLOCKER;

    static {
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> k = StampedLock.class;
            Class<?> wk = WNode.class;
            STATE = U.objectFieldOffset
                    (k.getDeclaredField("state"));
            WHEAD = U.objectFieldOffset
                    (k.getDeclaredField("whead"));
            WTAIL = U.objectFieldOffset
                    (k.getDeclaredField("wtail"));
            WSTATUS = U.objectFieldOffset
                    (wk.getDeclaredField("status"));
            WNEXT = U.objectFieldOffset
                    (wk.getDeclaredField("next"));
            WCOWAIT = U.objectFieldOffset
                    (wk.getDeclaredField("cowait"));
            Class<?> tk = Thread.class;
            PARKBLOCKER = U.objectFieldOffset
                    (tk.getDeclaredField("parkBlocker"));

        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
