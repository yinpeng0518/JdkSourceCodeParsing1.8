package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.Collection;

/**
 * {@link "https://mp.weixin.qq.com/s/N_C27deY0UnyXEifKoS-_w"}
 * {@link "https://mp.weixin.qq.com/s/V1emalLKHjkxh85Bzo_A1A"}
 * <p>
 * Java的内置锁一直都是备受争议的，在JDK 1.6之前，synchronized这个重量级锁其性能一直都是较为低下，虽然在1.6后，
 * 进行大量的锁优化策略,但是与Lock相比synchronized还是存在一些缺陷的：虽然synchronized提供了便捷性的隐式获取锁释放锁机制（基于JVM机制），
 * 但是它却缺少了获取锁与释放锁的可操作性，可中断、超时获取锁，且它为独占式在高并发场景下性能大打折扣。
 *
 * @author Doug Lea
 * @since 1.5
 */
public class ReentrantLock implements Lock, java.io.Serializable {
    private static final long serialVersionUID = 7373984872572414699L;

    /**
     * ReentrantLock用内部类Sync来管理锁，所以真正的获取锁和释放锁是由Sync的实现类来控制的；
     * Sync有两个实现，分别为NonfairSync（非公平锁）和FairSync（公平锁）
     */
    private final Sync sync;

    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = -5179523762034025860L;

        abstract void lock();

        //尝试非公平获取锁
        final boolean nonfairTryAcquire(int acquires) {
            final Thread current = Thread.currentThread(); //获取当前线程
            int c = getState();
            if (c == 0) { //若果c==0,说明没有线程占有锁
                if (compareAndSetState(0, acquires)) { //修改成功，则表示获取锁成功；修改失败，则表示获取锁失败。
                    setExclusiveOwnerThread(current);  //占有锁的线程设置为当前线程
                    return true;
                }
            } else if (current == getExclusiveOwnerThread()) { //如果得到锁的线程就是当前线程，发生锁重入
                int nextc = c + acquires;
                if (nextc < 0)
                    throw new Error("Maximum lock count exceeded"); //超过最大锁计数
                setState(nextc);
                return true;
            }
            return false;
        }

        //尝试释放锁
        protected final boolean tryRelease(int releases) {
            int c = getState() - releases;
            if (Thread.currentThread() != getExclusiveOwnerThread()) //释放锁的线程必须是当前占有锁的线程
                throw new IllegalMonitorStateException();
            boolean free = false;
            if (c == 0) {
                free = true;  //当c减到0时，才真正的释放锁；其他时候只是将锁重入的次数减1，但并没有释放掉锁
                setExclusiveOwnerThread(null); //释放锁后，将当前占有锁的线程设置为null。
            }
            setState(c);
            return free;
        }

        //此时占有锁的线程是不是当前线程
        protected final boolean isHeldExclusively() {
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        //获取当前占有锁的线程
        final Thread getOwner() {
            return getState() == 0 ? null : getExclusiveOwnerThread();
        }

        //获取锁重入次数
        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }

        //是否获得锁
        final boolean isLocked() {
            return getState() != 0;
        }

        private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            setState(0); // 重置为解锁状态
        }
    }

    /**
     * 非公平锁是直接进行CAS修改计数器看能不能加锁成功；
     * 如果加锁不成功则乖乖排队(调用acquire)；所以不管公平还是不公平；
     * 只要进到了AQS队列当中那么他就会排队；一朝排队；永远排队记住这点
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = 7316153563782823691L;

        //阻塞获取锁
        final void lock() {
            if (compareAndSetState(0, 1)) //先尝试获取锁
                setExclusiveOwnerThread(Thread.currentThread()); //获取到锁，设置拥有锁的线程为当前线程
            else
                acquire(1); //没获取到锁，则进去等待队列
        }

        //尝试获取锁
        protected final boolean tryAcquire(int acquires) {
            return nonfairTryAcquire(acquires);
        }
    }

    /**
     * 为什么需要公平锁？
     *
     * 饥饿：
     * a).CPU会根据不同的调度算法进行线程调度，将时间片分派给线程，那么就可能存在一个问题：某个线程可能一直得不到CPU分配的时间片，也就不能执行
     * b).一个线程因为得不到 CPU 运行时间，就会处于饥饿状态。如果该线程一直得不到CPU运行时间的机会，最终会被“饥饿致死”
     *
     * 导致线程饥饿的原因:
     * 1.高优先级线程吞噬所有的低优先级线程的CPU时间:
     * 每个线程都有独自的线程优先级，优先级越高的线程获得的CPU时间越多，如果并发状态下的线程包括一个低优先级的线程和多个高优先级的线程，
     * 那么这个低优先级的线程就有可能因为得不到CPU时间而饥饿.
     *
     * 2.线程被永久堵塞在一个等待进入同步块的状态:
     * 当同步锁被占用，线程处在BLOCKED状态等锁。当锁被释放，处在BLOCKED状态的线程都会去抢锁，
     * 抢到锁的线程可以执行，未抢到锁的线程继续在BLOCKED状态阻塞。问题在于这个抢锁过程中，
     * 到底哪个线程能抢到锁是没有任何保障的，这就意味着理论上是会有一个线程会一直抢不到锁，那么它将会永远阻塞下去的，导致饥饿。
     *
     * 3.线程在一个对象上等待，但一直没有未被唤醒:
     * 当一个线程调用 Object.wait()之后会被阻塞，直到被 Object.notify()唤醒。
     * 而 Object.notify()是随机选取一个线程唤醒的，不能保证哪一个线程会获得唤醒。因此如果多个线程都在一个对象的 wait()上阻塞，
     * 在没有调用足够多的 Object.notify()时，理论上是会有一个线程因为一直得不到唤醒而处于 WAITING 状态的，从而导致饥饿.
     *
     * 解决饥饿:
     * 解决饥饿的方案被称之为公平性，即所有线程能公平地获得运行机会。公平性针对获取锁而言的，
     * 如果一个锁是公平的，那么锁的获取顺序就应该符合请求上的绝对时间顺序，满足FIFO.
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -3000897897090466540L;

        final void lock() {
            acquire(1);  //直接进去等待队列
        }

        protected final boolean tryAcquire(int acquires) {
            //获取当前线程
            final Thread current = Thread.currentThread();
            //获取lock对象的上锁状态，如果锁是自由状态则=0，如果被上锁则为1，大于1表示重入
            int c = getState();

            if (c == 0) { //没人占用锁--->我要去上锁---->锁是自由状态
                if (!hasQueuedPredecessors() && //判断自己是否需要排队
                        compareAndSetState(0, acquires)) { //如果不需要排队则进行cas尝试加锁
                    setExclusiveOwnerThread(current); //如果加锁成功则把当前线程设置为拥有锁的线程
                    return true;
                }
                //如果C不等于0，而且当前线程不等于拥有锁的线程则不会进else if 直接返回false，加锁失败.
                //如果C不等于0，但是当前线程等于拥有锁的线程则表示这是一次重入，那么直接把状态+1表示重入次数+1
                //那么这里也侧面说明了reentrantlock是可以重入的，因为如果是重入也返回true，也能lock成功.
            } else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0)
                    throw new Error("Maximum lock count exceeded"); //超过最大锁计数
                setState(nextc);
                return true;
            }
            return false;
        }
    }

    //默认是非公平锁
    public ReentrantLock() {
        sync = new NonfairSync();
    }

    //fair=true 公平所； fair=false 非公平锁
    public ReentrantLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
    }

    public void lock() {
        sync.lock();
    }

    //可中断获取锁
    public void lockInterruptibly() throws InterruptedException {
        sync.acquireInterruptibly(1);
    }

    //尝试非公平获取锁
    public boolean tryLock() {
        return sync.nonfairTryAcquire(1);
    }

    //带超时时间获取锁
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireNanos(1, unit.toNanos(timeout));
    }

    public void unlock() {
        sync.release(1);
    }

    public Condition newCondition() {
        return sync.newCondition();
    }

    public int getHoldCount() {
        return sync.getHoldCount();
    }

    public boolean isHeldByCurrentThread() {
        return sync.isHeldExclusively();
    }

    public boolean isLocked() {
        return sync.isLocked();
    }

    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    protected Thread getOwner() {
        return sync.getOwner();
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
        Thread o = sync.getOwner();
        return super.toString() + ((o == null) ?
                "[Unlocked]" :
                "[Locked by thread " + o.getName() + "]");
    }
}
