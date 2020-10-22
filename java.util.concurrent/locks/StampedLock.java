package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.LockSupport;

/**
 * {@link "https://juejin.im/post/6844903865154797581#heading-0"}
 * {@link "https://mp.weixin.qq.com/s/NPx3y5sSuiIOBrxfqEmvxQ"}
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
    private static final long WBIT = 1L << LG_READERS;  //写锁获取成功，state增加的值，写锁标志位（1000 0000）128
    private static final long RBITS = WBIT - 1L; //在获取当前读锁的个数，判断当前stampedLock是属于读锁的状态，127 （读状态标识  0000... 0000 0111 1111）
    private static final long RFULL = RBITS - 1L;  //读锁最大数量，126 （0000... 0000 0111 1110）
    private static final long ABITS = RBITS | WBIT; //包含读锁标志位和写锁标志位和起来，在获取锁和释放锁中使用，比如用于判断state是否处于读锁还是写锁，还是无锁状态（0000... 0000 1111 1111）
    private static final long SBITS = ~RBITS; //用于在乐观锁和释放锁使用 （1111... 1111 1000 0000）
    private static final long ORIGIN = WBIT << 1; //StampedLock初始化,state的初始值（1 0000 0000），256
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
    private transient int readerOverflow;   //读锁溢出(超过最大容量126)时，记录额外的读锁大小

    //state被初始化为256，写锁标志位128，读锁位
    public StampedLock() {
        state = ORIGIN;
    }

    //获取写锁，如果获取失败，进入阻塞，writeLock方法不支持中断操作
    public long writeLock() {
        long s, next;
        return ((((s = state) & ABITS) == 0L && // 没有读写锁
                U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ?  // cas操作尝试获取写锁
                next : acquireWrite(false, 0L)); // 获取成功后返回next，失败则进行后续处理
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

    //根据传入的stamp释放写锁
    public void unlockWrite(long stamp) {
        WNode h;
        //如果当前StampedLock的锁状态state和传入进来的stamp不匹配或者传入进来的不是写锁标志位，抛出异常
        if (state != stamp || (stamp & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        /**
         * 释放写锁为什么不直接减去stamp，再加上ORIGIN，而是(stamp += WBIT) == 0L ? ORIGIN : stamp来释放写锁，
         * 位操作表示如下：stamp += WBIT 即 0010 0000 0000 = 0001 1000 0000 + 0000 1000 0000  这一步操作是重点！！！
         * 写锁的释放并不是像ReentrantReadWriteLock一样+1然后-1，而是通过再次加0000 1000 0000来使高位每次都产生变化，为什么要这样做？
         * 直接减掉0000 1000 0000不就可以了吗？这就是为了后面乐观锁做铺垫，让每次写锁都留下痕迹。
         * 大家知道cas ABA的问题，字母A变化为B能看到变化，如果在一段时间内从A变到B然后又变到A，在内存中自会显示A，而不能记录变化的过程。
         * 在StampedLock中就是通过每次对高位加0000 1000 0000来达到记录写锁操作的过程，可以通过下面的步骤理解:
         *   第一次获取写锁： 0001 0000 0000 + 0000 1000 0000 = 0001 1000 0000
         *   第一次释放写锁： 0001 1000 0000 + 0000 1000 0000 = 0010 0000 0000
         *   第二次获取写锁： 0010 0000 0000 + 0000 1000 0000 = 0010 1000 0000
         *   第二次释放写锁： 0010 1000 0000 + 0000 1000 0000 = 0011 0000 0000
         *   第n次获取写锁:  1110 0000 0000 + 0000 1000 0000 = 1110 1000 0000
         *   第n次释放写锁:  1110 1000 0000 + 0000 1000 0000 = 1111 0000 0000
         * 可以看到第8位在获取和释放写锁时会产生变化，也就是说第8位是用来表示写锁状态的，前7位是用来表示读锁状态的，8位之后是用来表示写锁的获取次数的。
         * 这样就有效的解决了ABA问题，留下了每次写锁的记录，也为后面乐观锁检查变化提供了基础。
         */
        state = (stamp += WBIT) == 0L ? ORIGIN : stamp; //加0000 1000 0000来记录写锁的变化，同时改变写锁状态
        //头结点不为空，并且头结点的状态不为0
        if ((h = whead) != null && h.status != 0)
            release(h); //唤醒头结点的下一有效节点
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

    //唤醒头结点的下一有效节点
    private void release(WNode h) {
        if (h != null) { //头结点不为空
            WNode q;
            Thread w;
            //如果头结点的状态为等待状态，将其状态设置为0
            U.compareAndSwapInt(h, WSTATUS, WAITING, 0);
            //从尾节点开始，到头节点结束，寻找状态为等待或者0的有效节点
            if ((q = h.next) == null || q.status == CANCELLED) {
                for (WNode t = wtail; t != null && t != h; t = t.prev)
                    if (t.status <= 0)
                        q = t;
            }
            //如果寻找到有效节点不为空，并且其对应的线程也不为空，唤醒其线程
            if (q != null && (w = q.thread) != null)
                U.unpark(w);
        }
    }

    /**
     * 尝试自旋的获取写锁, 获取不到则阻塞线程
     *
     * @param interruptible 表示检测中断, 如果线程被中断过, 则最终返回INTERRUPTED
     * @param deadline      如果非0, 则表示限时获取
     * @return 非0表示获取成功, INTERRUPTED表示中途被中断过
     */
    private long acquireWrite(boolean interruptible, long deadline) {
        WNode node = null, p;
        /**
         * 自旋入队操作:
         * 如果没有任何锁被占用, 则立即尝试获取写锁, 获取成功则返回.
         * 如果存在锁被使用, 则将当前线程包装成独占结点, 并插入等待队列尾部
         */
        for (int spins = -1; ; ) {
            long m, s, ns;
            //如果当前state等于256，属于无锁状态，直接加写锁，如果加锁成功直接返回
            if ((m = (s = state) & ABITS) == 0L) {  //没有任何锁被占用 (0000 1111 1111 & 0000 1000 0000  = 0000 1000 0000)
                if (U.compareAndSwapLong(this, STATE, s, ns = s + WBIT))  //尝试立即获取写锁
                    return ns;  //获取成功直接返回
            } else if (spins < 0)
                //如果spins小于0，并且当前的StampedLock属于写锁状态，以及头尾节点相等，spins赋值SPINS,让其当前线程自旋一段时间获取写锁
                spins = (m == WBIT  //true 表示写锁被占用 (0000 1111 1111 & 0000 1000 0000  = 0000 1000 0000)
                        && wtail == whead) //表示等待队列只有一个节点
                        ? SPINS : 0;  //自旋64次
            else if (spins > 0) { //自旋获取写锁
                if (LockSupport.nextSecondarySeed() >= 0)
                    --spins;
            } else if ((p = wtail) == null) {  //如果当前队列为空队列，即尾节点为空，初始化队列
                WNode hd = new WNode(WMODE, null); //构造写模式的头节点
                if (U.compareAndSwapObject(this, WHEAD, null, hd)) //设置头结点
                    wtail = hd; //如果头结点设置成功，将其尾节点设置为头结点
            } else if (node == null)   //将当前线程包装成写结点
                node = new WNode(WMODE, p); //前驱节点为上一次的尾节点
            else if (node.prev != p)  //如果尾节点已经改变，重新设置当前节点的前驱节点
                node.prev = p;
            else if (U.compareAndSwapObject(this, WTAIL, p, node)) { //将其当前节点设置为尾节点
                p.next = node;
                break;
            }
        }
        //阻塞当前线程，再阻塞当前线程之前，如果头节点和尾节点相等，让其自旋一段时间获取写锁。如果头结点不为空，释放头节点的cowait队列
        for (int spins = -1; ; ) {
            WNode h, np, pp;
            int ps;
            if ((h = whead) == p) {   //如果当前结点是队首结点(p是当前节点的前驱节点), 则立即尝试获取写锁
                if (spins < 0) //设置初始自旋的值
                    spins = HEAD_SPINS; //设置自旋次数 1024
                else if (spins < MAX_HEAD_SPINS)
                    spins <<= 1; //如果spins还是小于MAX_HEAD_SPINS,将其扩大2倍
                for (int k = spins; ; ) { //自旋获取写锁
                    long s, ns;
                    //如果写锁设置成功，将其当前节点的前驱节点设置为空，并且将其节点设置为头节点
                    if (((s = state) & ABITS) == 0L) {  //没有任何锁被占用
                        if (U.compareAndSwapLong(this, STATE, s, ns = s + WBIT)) {  // CAS修改State: 占用写锁
                            whead = node;   //将当前节点设置为头节点
                            node.prev = null; //将其当前节点的前驱节点设置为空
                            return ns;
                        }
                    } else if (LockSupport.nextSecondarySeed() >= 0 &&
                            --k <= 0)  //LockSupport.nextSecondarySeed() >= 0永真，k做自减操作
                        break;
                }

                // 唤醒头结点的栈中的所有读线程
            } else if (h != null) {  //如果头结点不为空
                WNode c;
                Thread w;
                while ((c = h.cowait) != null) {  //如果头结点的cowait队列（RMODE的节点）不为空，唤醒cowait队列
                    //cowait节点和对应的节点都不为空唤醒其线程，循环的唤醒cowait节点队列中Thread不为空的线程
                    if (U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                            (w = c.thread) != null)
                        U.unpark(w);
                }
            }
            if (whead == h) { //如果头结点不变
                if ((np = node.prev) != p) {
                    if (np != null)
                        (p = np).next = node;   // stale
                } else if ((ps = p.status) == 0)   // 将当前结点的前驱置为WAITING, 表示当前结点会进入阻塞, 前驱将来需要唤醒我
                    U.compareAndSwapInt(p, WSTATUS, 0, WAITING); //如果当前节点的前驱节点状态为0，将其前驱节点设置为等待状态
                else if (ps == CANCELLED) { //如果当前节点的前驱节点状态为取消
                    if ((pp = p.prev) != null) { //重新设置当前节点的前驱节点
                        node.prev = pp;
                        pp.next = node;
                    }
                } else {      //阻塞当前调用线程
                    long time; // 参数0表示不超时
                    if (deadline == 0L)
                        time = 0L;
                    else if ((time = deadline - System.nanoTime()) <= 0L) //如果时间已经超时，取消当前的等待节点
                        return cancelWaiter(node, node, false);
                    Thread wt = Thread.currentThread(); //获取当前线程
                    U.putObject(wt, PARKBLOCKER, this); //设置线程Thread的parkblocker属性，表示当前线程被谁阻塞，用于监控线程使用
                    node.thread = wt; //将其当前线程设置为当前节点
                    //当前节点的前驱节点为等待状态，并且队列的头节点和尾节点不相等或者StampedLock当前状态为有锁状态，队列头节点没变，当前节点的前驱节点没变，阻塞当前线程
                    if (p.status < 0 && (p != h || (state & ABITS) != 0L) &&
                            whead == h && node.prev == p)
                        //模拟阻塞当前线程，只有调用UnSafe.unpark()唤醒，如果time不等于0，时间到也会自动唤醒
                        U.park(false, time);  // emulate LockSupport.park
                    node.thread = null; //当前节点的线程置为空
                    U.putObject(wt, PARKBLOCKER, null); //当前线程的监控对象也置为空
                    if (interruptible && Thread.interrupted()) //如果传入的参数interruptible为true，并且当前线程中断，取消当前节点
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

    //取消等待节点
    private long cancelWaiter(WNode node, WNode group, boolean interrupted) {
        //node和group为同一节点，要取消的节点，都不为空时
        if (node != null && group != null) {
            Thread w;
            node.status = CANCELLED; //将其当前节点的状态设置为取消状态
            //如果当前要取消节点的cowait队列不为空，将其cowait队列中取消的节点移除
            for (WNode p = group, q; (q = p.cowait) != null; ) {
                if (q.status == CANCELLED) {
                    U.compareAndSwapObject(p, WCOWAIT, q, q.cowait);
                    p = group; // restart
                } else
                    p = q;
            }
            if (group == node) {  //group和node为同一节点
                for (WNode r = group.cowait; r != null; r = r.cowait) { //唤醒状态没有取消的cowait队列中的节点
                    if ((w = r.thread) != null)
                        U.unpark(w);
                }
                //将其当前取消节点的前驱节点的下一个节点设置为当前取消节点的next节点
                for (WNode pred = node.prev; pred != null; ) {
                    WNode succ, pp;
                    //如果当前取消节点的下一个节点为空或者是取消状态，从尾节点开始，寻找有效的节点
                    while ((succ = node.next) == null || succ.status == CANCELLED) {
                        WNode q = null;
                        //从尾节点开始寻找当前取消节点的下一个节点
                        for (WNode t = wtail; t != null && t != node; t = t.prev)
                            if (t.status != CANCELLED)
                                q = t;
                        //如果当前取消节点的next节点和从尾节点寻找到的节点相等，或者将其寻找的节点q设置为下一个节点成功
                        if (succ == q ||
                                U.compareAndSwapObject(node, WNEXT,
                                        succ, succ = q)) {
                            //判断当前取消节点的下一个节点为空并且当前取消节点为尾节点（说明当前取消节点就是尾节点，需要重新将其前驱节点设置成尾节点）
                            if (succ == null && node == wtail)
                                U.compareAndSwapObject(this, WTAIL, node, pred); //将当前取消节点的前驱节点设置为尾节点
                            break;
                        }
                    }
                    //如果当前取消节点的前驱节点的下一节点为当前取消节点
                    if (pred.next == node)
                        //将其前驱节点的下一节点设置为当前取消节点的next有效节点
                        U.compareAndSwapObject(pred, WNEXT, node, succ);
                    //唤醒当前取消节点的下一节点
                    if (succ != null && (w = succ.thread) != null) {
                        succ.thread = null;
                        U.unpark(w);
                    }
                    //如果当前取消节点的前驱节点状态不是取消状态，或者其前驱节点的前驱节点为空，直接退出循环
                    if (pred.status != CANCELLED || (pp = pred.prev) == null)
                        break;
                    node.prev = pp;  //重新设置当前取消节点的前驱节点
                    U.compareAndSwapObject(pp, WNEXT, pred, succ); //重新设置pp的下一节点
                    pred = pp; //将其前驱节点设置为pp，重新循环
                }
            }
        }
        WNode h;
        while ((h = whead) != null) {  //头节点不为空
            long s;
            WNode q;
            //头节点的下一节点为空或者是取消状态，从尾节点开始寻找有效的节点（包括等待状态，和运行状态）
            if ((q = h.next) == null || q.status == CANCELLED) {
                for (WNode t = wtail; t != null && t != h; t = t.prev)
                    if (t.status <= 0)
                        q = t;
            }
            //如果头节点没有改变
            if (h == whead) {
                //头节点的下一有效节点不为空，并且头节点的状态为0，并且当前StampedLock的不为写锁状态，并且头节点的下一节点为读模式，唤醒头结点的下一节点
                if (q != null && h.status == 0 &&
                        ((s = state) & ABITS) != WBIT &&
                        (s == 0L || q.mode == RMODE))
                    release(h);  //唤醒头结点的下一有效节点
                break;
            }
        }
        //如果当前线程被中断或者传入进来的interrupted为true，直接返回中断标志位，否则返回0
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
