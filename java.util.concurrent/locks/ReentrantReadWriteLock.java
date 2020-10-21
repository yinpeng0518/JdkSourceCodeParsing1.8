package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.Collection;

/**
 * {@link "https://mp.weixin.qq.com/s/qInA1e_-eITwFoVzFfugjA"}
 *
 * ReentrantReadWriteLock(读写锁):
 * 读写锁允许共享资源在同一时刻可以被多个读线程访问，但是在写线程访问时，所有的读线程和其他的写线程都会被阻塞。
 *
 * @author Doug Lea
 * @since 1.5
 */
public class ReentrantReadWriteLock implements ReadWriteLock, java.io.Serializable {
    private static final long serialVersionUID = -6992448646407690164L;

    private final ReentrantReadWriteLock.ReadLock readerLock;  // 读锁
    private final ReentrantReadWriteLock.WriteLock writerLock; // 写锁
    final Sync sync; // 锁的主体AQS

    public ReentrantReadWriteLock() {
        this(false); //默认非公平
    }

    public ReentrantReadWriteLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
        readerLock = new ReadLock(this);
        writerLock = new WriteLock(this);
    }

    public ReentrantReadWriteLock.WriteLock writeLock() {
        return writerLock;
    }

    public ReentrantReadWriteLock.ReadLock readLock() {
        return readerLock;
    }

    //ReentrantReadWriteLock的同步实现。子类分为公平版本和不公平版本
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 6317671515068378041L;

        // 将state这个int变量分为高16位和低16位，高16位记录读锁状态，低16位记录写锁状态
        static final int SHARED_SHIFT = 16;
        static final int SHARED_UNIT = (1 << SHARED_SHIFT);
        static final int MAX_COUNT = (1 << SHARED_SHIFT) - 1;
        static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;

        /**
         * 获取读锁的状态，读锁的获取次数(包括重入)
         * c无符号补0右移16位，获得高16位
         */
        static int sharedCount(int c) {
            return c >>> SHARED_SHIFT;
        }

        /**
         * 获取写锁的状态，写锁的重入次数
         * c & 0x0000FFFF，将高16位全部抹去，获得低16位
         */
        static int exclusiveCount(int c) {
            return c & EXCLUSIVE_MASK;
        }

        //sycn中提供了一个HoldCounter类，类似计数器，用于记录一个线程读锁的重入次数。将HoldCounter通过ThreadLocal与线程绑定。
        static final class HoldCounter {
            int count = 0; // 读锁重入次数

            // 使用id而不是reference来避免垃圾保留
            final long tid = getThreadId(Thread.currentThread());  //返回给定线程的线程id
        }

        //本地线程计数器
        static final class ThreadLocalHoldCounter extends ThreadLocal<HoldCounter> {
            // 重写初始化方法，在没有进行set的情况下，获取的都是该HoldCounter值
            public HoldCounter initialValue() {
                return new HoldCounter();
            }
        }

        private transient ThreadLocalHoldCounter readHolds; //记录当前线程持有的可重入读锁的数量
        private transient HoldCounter cachedHoldCounter;    //记录"最后一个获取读锁的线程"的读锁重入次数，用于缓存提高性能
        private transient Thread firstReader = null;        //第一个获取读锁的线程(并且其未释放读锁)
        private transient int firstReaderHoldCount;         //第一个获取读锁的线程重入的读锁数量

        Sync() {
            readHolds = new ThreadLocalHoldCounter();
            setState(getState());
        }

        abstract boolean readerShouldBlock(); //检查AQS队列中的情况，看是当前线程是否可以获取读锁，返回true表示当前不能获取读锁。

        abstract boolean writerShouldBlock(); //检查AQS队列中的情况，看是当前线程是否可以获取写锁，返回true表示当前不能获取写锁。

        /**
         * 释放写锁，修改写锁标志位和exclusiveOwnerThread
         * 如果这个写锁释放之后，没有线程占用写锁了，返回true
         */
        protected final boolean tryRelease(int releases) {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int nextc = getState() - releases;
            boolean free = exclusiveCount(nextc) == 0;
            if (free)
                setExclusiveOwnerThread(null);
            setState(nextc);
            return free;
        }

        //尝试获取写锁
        protected final boolean tryAcquire(int acquires) {
            Thread current = Thread.currentThread();
            int c = getState();
            int w = exclusiveCount(c);  //写锁标志位

            // 进到这个if里，c!=0表示有线程占用锁
            // 当有线程占用锁时，只有一种情况是可以获取写锁的，那就是写锁重入
            if (c != 0) {
                /**
                 * 两种情况返回false
                 * 1.(c != 0 & w == 0)
                 * c!=0表示标志位!=0，w==0表示写锁标志位==0，总的标志位不为0而写锁标志位(低16位)为0，只能是读锁标志位(高16位)不为0
                 * 也就是有线程占用读锁，此时不能获取写锁，返回false
                 *
                 * 2.(c != 0 & w != 0 & current != getExclusiveOwnerThread())
                 * c != 0 & w != 0 表示写锁标志位不为0，有线程占用写锁
                 * current != getExclusiveOwnerThread() 占用写锁的线程不是当前线程
                 * 不能获取写锁，返回false
                 */
                if (w == 0 || current != getExclusiveOwnerThread())
                    return false;
                // 重入次数不能超过2^16-1
                if (w + exclusiveCount(acquires) > MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");

                /**
                 * 修改标志位
                 * 这里修改标志位为什么没有用CAS原子操作呢？
                 * 因为到这里肯定是写锁重入了，写锁是独占锁，不会有其他线程来捣乱。
                 */
                setState(c + acquires);
                return true;
            }

            /**
             * 到这里表示锁是没有被线程占用的，因为锁被线程占用的情况在上个if里处理并返回了
             * 所以这里直接检查AQS队列情况，没问题的话CAS修改标志位获取锁
             */
            if (writerShouldBlock() ||  //检查AQS队列中的情况，看是当前线程是否可以获取写锁
                    !compareAndSetState(c, c + acquires)) //修改写锁标志位
                return false; // 获取写锁失败

            setExclusiveOwnerThread(current); // 获取写锁成功，将AQS.exclusiveOwnerThread置为当前线程
            return true;
        }

        /**
         * 释放读锁
         * 当前线程释放读锁之后，没有线程占用锁，返回true
         */
        protected final boolean tryReleaseShared(int unused) {
            Thread current = Thread.currentThread();
            // 处理firstReader、cachedHoldCounter、readHolds获取读锁线程及读锁重入次数
            if (firstReader == current) {
                if (firstReaderHoldCount == 1)
                    firstReader = null;
                else
                    firstReaderHoldCount--;
            } else {
                HoldCounter rh = cachedHoldCounter;
                if (rh == null || rh.tid != getThreadId(current))
                    rh = readHolds.get();
                int count = rh.count;
                if (count <= 1) {
                    readHolds.remove();
                    if (count <= 0)
                        throw unmatchedUnlockException();
                }
                --rh.count;
            }
            for (; ; ) {
                int c = getState();
                int nextc = c - SHARED_UNIT; // state第17位-1，也就是读锁状态标志位-1
                if (compareAndSetState(c, nextc)) // CAS设置state，CAS失败自旋进入下一次for循环
                    return nextc == 0; //state=0表示没有线程占用锁，返回true
            }
        }

        private IllegalMonitorStateException unmatchedUnlockException() {
            return new IllegalMonitorStateException(
                    "attempt to unlock read lock, not locked by current thread");
        }

        //尝试获取读锁，获取到锁返回1，获取不到返回-1
        protected final int tryAcquireShared(int unused) {
            Thread current = Thread.currentThread();
            int c = getState();
            /**
             * 根据锁的状态判断可以获取读锁的情况：
             * 1. 读锁写锁都没有被占用
             * 2. 只有读锁被占用
             * 3. 写锁被自己线程占用
             * 总结一下，只有在其它线程持有写锁时，不能获取读锁，其它情况都可以去获取。
             */
            if (exclusiveCount(c) != 0 &&  // 写锁被占用
                    getExclusiveOwnerThread() != current) // 持有写锁的不是当前线程
                return -1;
            int r = sharedCount(c);  //获取读锁的状态，读锁的获取次数(包括重入)
            if (!readerShouldBlock() && //检查AQS队列中的情况，看是当前线程是否可以获取读锁
                    r < MAX_COUNT &&    //读锁的标志位只有16位，最多之能有2^16-1个线程获取读锁或重入
                    compareAndSetState(c, c + SHARED_UNIT)) {  //在state的第17位加1，也就是将读锁标志位加1
                //到这里已经获取到读锁了,以下是修改记录获取读锁的线程和重入次数，以及缓存firstReader和cachedHoldCounter
                if (r == 0) { // 读锁数量为0
                    firstReader = current;  // 设置第一个读线程
                    firstReaderHoldCount = 1;  // 读线程占用的资源数为1
                } else if (firstReader == current) { // 当前线程为第一个读线程
                    firstReaderHoldCount++;  // 占用资源数加1
                } else {  // 读锁数量不为0并且不为当前线程
                    HoldCounter rh = cachedHoldCounter;  // 获取计数器
                    if (rh == null || rh.tid != getThreadId(current)) // 计数器为空或者计数器的tid不为当前正在运行的线程的tid
                        cachedHoldCounter = rh = readHolds.get(); // 获取当前线程对应的计数器
                    else if (rh.count == 0) // 计数为0
                        readHolds.set(rh); // 设置
                    rh.count++;
                }
                return 1;
            }

            /**
             * 到这里说明没有获取到读锁，因为上面代码获取到读锁的话已经在上一个if里返回1了
             * 锁的状态是满足获取读锁的，因为不满足的上面返回-1了
             * 所以没有获取到读锁的原因：AQS队列不满足获取读锁条件，或者CAS失败，或者16位标志位满了
             * 像CAS失败这种原因，是一定要再尝试获取的，所以这里再次尝试获取读锁。
             */
            return fullTryAcquireShared(current);
        }

        /**
         * 再次尝试获取读锁:
         *
         * tryAcquireShared()方法中因为CAS抢锁失败等原因没有获取到读锁的，
         * fullTryAcquireShared()再次尝试获取读锁。
         * 此外，fullTryAcquireShared()还处理了读锁重入的情况
         */
        final int fullTryAcquireShared(Thread current) {
            HoldCounter rh = null;
            for (; ; ) { // 无限循环
                int c = getState();  // 获取状态
                if (exclusiveCount(c) != 0) { // 写线程数量不为0
                    // 仍然是先检查锁状态：在其它线程持有写锁时，不能获取读锁，返回-1
                    if (getExclusiveOwnerThread() != current) // 不为当前线程
                        return -1;
                } else if (readerShouldBlock()) { // 写线程数量为0,并且读线程被阻塞
                    /**
                     * exclusiveCount(c) == 0 写锁没有被占用
                     * readerShouldBlock() == true，AQS同步队列中的线程在等锁，当前线程不能抢读锁
                     * 既然当前线程不能抢读锁，为什么没有直接返回呢？
                     * 因为这里还有一种情况是可以获取读锁的，那就是读锁重入。
                     * 以下代码就是检查如果不是重入的话，return -1，不能继续往下获取锁。
                     */
                    if (firstReader == current) { // 当前线程为第一个读线程
                        // assert firstReaderHoldCount > 0;
                    } else { // 当前线程不为第一个读线程
                        if (rh == null) {  // 计数器为空
                            rh = cachedHoldCounter;
                            if (rh == null || rh.tid != getThreadId(current)) { //计数器为空或者计数器的tid不为当前正在运行的线程的tid
                                rh = readHolds.get();
                                if (rh.count == 0)
                                    readHolds.remove();
                            }
                        }
                        if (rh.count == 0)
                            return -1;
                    }
                }
                if (sharedCount(c) == MAX_COUNT) // 读锁数量为最大值，抛出异常
                    throw new Error("Maximum lock count exceeded");

                //CAS修改读锁标志位，修改成功表示获取到读锁；CAS失败，则进入下一次for循环继续CAS抢锁
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    if (sharedCount(c) == 0) { // 读线程数量为0
                        firstReader = current;   //设置第一个读线程
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        if (rh == null)
                            rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;  //锁重入+1
                        cachedHoldCounter = rh;
                    }
                    return 1;
                }
            }
        }

        //尝试获取写锁
        final boolean tryWriteLock() {
            Thread current = Thread.currentThread();
            int c = getState();
            if (c != 0) {
                int w = exclusiveCount(c);
                if (w == 0 || current != getExclusiveOwnerThread())
                    return false;
                if (w == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
            }
            if (!compareAndSetState(c, c + 1))
                return false;
            setExclusiveOwnerThread(current);
            return true;
        }

        //尝试获取读锁
        final boolean tryReadLock() {
            Thread current = Thread.currentThread();
            for (; ; ) {
                int c = getState();
                if (exclusiveCount(c) != 0 &&
                        getExclusiveOwnerThread() != current)
                    return false;
                int r = sharedCount(c);
                if (r == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    if (r == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        HoldCounter rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            cachedHoldCounter = rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                    }
                    return true;
                }
            }
        }

        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        final Thread getOwner() {
            // Must read state before owner to ensure memory consistency
            return ((exclusiveCount(getState()) == 0) ?
                    null :
                    getExclusiveOwnerThread());
        }

        final int getReadLockCount() {
            return sharedCount(getState());
        }

        final boolean isWriteLocked() {
            return exclusiveCount(getState()) != 0;
        }

        final int getWriteHoldCount() {
            return isHeldExclusively() ? exclusiveCount(getState()) : 0;
        }

        final int getReadHoldCount() {
            if (getReadLockCount() == 0)
                return 0;

            Thread current = Thread.currentThread();
            if (firstReader == current)
                return firstReaderHoldCount;

            HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == getThreadId(current))
                return rh.count;

            int count = readHolds.get().count;
            if (count == 0) readHolds.remove();
            return count;
        }

        /**
         * Reconstitutes the instance from a stream (that is, deserializes it).
         */
        private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            readHolds = new ThreadLocalHoldCounter();
            setState(0); // reset to unlocked state
        }

        final int getCount() {
            return getState();
        }
    }

    //非公平锁
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -8159625535654395037L;

        //对于非公平锁来说，不需要关心队列中的情况，有机会直接尝试抢锁就好了，所以直接返回false。
        final boolean writerShouldBlock() {
            return false; // writers can always barge
        }

        /**
         * 对于非公平锁来说，原本是不需要关心队列中的情况，有机会直接尝试抢锁就好了，这里问什么会限制获取锁呢？
         * 这里给写锁定义了更高的优先级，如果队列中第一个等锁的线程请求的是写锁，那么当前线程就不能跟那个马上就要获取写锁的线程抢，这样做很好的避免了写锁饥饿。
         */
        final boolean readerShouldBlock() {
            return apparentlyFirstQueuedIsExclusive();
        }
    }

    //公平锁
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -2274990926593161451L;

        //对于公平锁来说，如果队列中还有线程在等锁，就不允许新来的线程获得锁，必须进入队列排队。
        final boolean writerShouldBlock() {
            return hasQueuedPredecessors();
        }

        //对于公平锁来说，如果队列中还有线程在等锁，就不允许新来的线程获得锁，必须进入队列排队。
        final boolean readerShouldBlock() {
            return hasQueuedPredecessors(); //判断同步队列中是否有排队等待的线程
        }
    }

    //读锁实现类
    public static class ReadLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -5992448646407690164L;
        private final Sync sync;

        protected ReadLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        /**
         * 获取读锁。
         * 如果写锁没有被其他线程持有，则获取读锁并立即返回。
         * 如果写锁被另一个线程持有，那么当前线程将被禁用，用于线程调度，并处于休眠状态，直到获得读锁。
         */
        public void lock() {
            sync.acquireShared(1);
        }

        public void lockInterruptibly() throws InterruptedException {
            sync.acquireSharedInterruptibly(1);
        }

        public boolean tryLock() {
            return sync.tryReadLock();
        }

        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
        }

        //释放读锁
        public void unlock() {
            sync.releaseShared(1);
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }

        public String toString() {
            int r = sync.getReadLockCount();
            return super.toString() +
                    "[Read locks = " + r + "]";
        }
    }

    //写锁实现类
    public static class WriteLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -4992448646407690164L;
        private final Sync sync;

        protected WriteLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        public void lock() {
            sync.acquire(1);
        }

        public void lockInterruptibly() throws InterruptedException {
            sync.acquireInterruptibly(1);
        }

        public boolean tryLock() {
            return sync.tryWriteLock();
        }

        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            return sync.tryAcquireNanos(1, unit.toNanos(timeout));
        }

        public void unlock() {
            sync.release(1);
        }

        public Condition newCondition() {
            return sync.newCondition();
        }

        public String toString() {
            Thread o = sync.getOwner();
            return super.toString() + ((o == null) ?
                    "[Unlocked]" :
                    "[Locked by thread " + o.getName() + "]");
        }

        public boolean isHeldByCurrentThread() {
            return sync.isHeldExclusively();
        }

        public int getHoldCount() {
            return sync.getWriteHoldCount();
        }
    }

    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    protected Thread getOwner() {
        return sync.getOwner();
    }

    public int getReadLockCount() {
        return sync.getReadLockCount();
    }

    public boolean isWriteLocked() {
        return sync.isWriteLocked();
    }

    public boolean isWriteLockedByCurrentThread() {
        return sync.isHeldExclusively();
    }

    public int getWriteHoldCount() {
        return sync.getWriteHoldCount();
    }

    public int getReadHoldCount() {
        return sync.getReadHoldCount();
    }

    protected Collection<Thread> getQueuedWriterThreads() {
        return sync.getExclusiveQueuedThreads();
    }

    protected Collection<Thread> getQueuedReaderThreads() {
        return sync.getSharedQueuedThreads();
    }

    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject) condition);
    }

    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject) condition);
    }

    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject) condition);
    }

    public String toString() {
        int c = sync.getCount();
        int w = Sync.exclusiveCount(c);
        int r = Sync.sharedCount(c);

        return super.toString() +
                "[Write locks = " + w + ", Read locks = " + r + "]";
    }

    //返回给定线程的线程id
    static final long getThreadId(Thread thread) {
        return UNSAFE.getLongVolatile(thread, TID_OFFSET);
    }

    private static final sun.misc.Unsafe UNSAFE;
    private static final long TID_OFFSET; // 线程ID的偏移地址

    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> tk = Thread.class;
            // 获取线程的tid字段的内存地址
            TID_OFFSET = UNSAFE.objectFieldOffset
                    (tk.getDeclaredField("tid"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
