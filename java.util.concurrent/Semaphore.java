package java.util.concurrent;

import java.util.Collection;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * Semaphore:信号量,它保存了一系列的许可(permits),每次调用acquire()都将消耗一个许可,每次调用release()都将归还一个许可.
 * Semaphore 通常用于限制同一时间对共享资源的访问次数上,也就是常说的限流.
 * Semaphore 中包含了一个实现了AQS的同步器Sync,以及它的两个子类FairSync和NonFairSync,这说明Semaphore也是区分公平模式和非公平模式的.
 *
 * (1)如何动态增加n个许可?
 * 答: 调用release(int permits)即可.
 * 我们知道释放许可的时候state的值会相应增加,再回头看看释放许可的源码,发现与ReentrantLock的释放锁还是有点区别的,
 * Semaphore释放许可的时候并不会检查当前线程有没有获取过许可,所以可以调用释放许可的方法动态增加一些许可.
 *
 * (2)如何实现限流?
 * 答:限流,即在流量突然增大的时候,上层要能够限制住突然的大流量对下游服务的冲击,在分布式系统中限流一般做在网关层,
 * 当然在个别功能中也可以自己简单地来限流,比如秒杀场景,假如只有10个商品需要秒杀,那么,服务本身可以限制同时只进来100个请求,
 * 其它请求全部作废,这样服务的压力也不会太大.
 *
 * @author Doug Lea
 * @since 1.5
 */
public class Semaphore implements java.io.Serializable {
    private static final long serialVersionUID = -3222578661600680210L;
    private final Sync sync;

    // 信号量的同步实现,使用AQS状态表示许可,分为公平和非公平.
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 1192457210091910933L;

        // 构造方法,传入许可次数,放入state中
        Sync(int permits) {
            setState(permits);
        }

        // 获取许可次数
        final int getPermits() {
            return getState();
        }

        // 非公平模式尝试获取许可
        final int nonfairTryAcquireShared(int acquires) {
            for (; ; ) {
                // 看看还有几个许可
                int available = getState();
                // 减去这次需要获取的许可还剩下几个许可
                int remaining = available - acquires;
                if (remaining < 0 ||                           // 如果剩余许可小于0了则直接返回
                        compareAndSetState(available, remaining))  // 如果剩余许可不小于0，则尝试原子更新state的值，成功了返回剩余许可
                    return remaining;
            }
        }

        // 释放许可
        protected final boolean tryReleaseShared(int releases) {
            for (; ; ) {
                // 看看还有几个许可
                int current = getState();
                // 加上这次释放的许可
                int next = current + releases;
                // 检测溢出
                if (next < current) // overflow
                    throw new Error("Maximum permit count exceeded");
                // 如果原子更新state的值成功,就说明释放许可成功,则返回true.
                if (compareAndSetState(current, next))
                    return true;
            }
        }

        // 减少许可
        final void reducePermits(int reductions) {
            for (; ; ) {
                // 看看还有几个许可
                int current = getState();
                // 减去将要减少的许可
                int next = current - reductions;
                // 检测溢出
                if (next > current) // underflow
                    throw new Error("Permit count underflow");
                // 原子更新state的值，成功了返回true
                if (compareAndSetState(current, next))
                    return;
            }
        }

        // 销毁许可
        final int drainPermits() {
            for (; ; ) {
                // 看看还有几个许可
                int current = getState();
                if (current == 0 ||                      // 如果为0，直接返回
                        compareAndSetState(current, 0))  // 如果不为0，把state原子更新为0
                    return current;
            }
        }
    }

    // 非公平版本
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -2694183684443567898L;

        // 构造方法,调用父类的构造方法.
        NonfairSync(int permits) {
            super(permits);
        }

        // 尝试获取许可,调用父类的nonfairTryAcquireShared()方法.
        protected int tryAcquireShared(int acquires) {
            return nonfairTryAcquireShared(acquires);
        }
    }

    // 公平版本
    static final class FairSync extends Sync {
        private static final long serialVersionUID = 2014338818796000944L;

        // 构造方法,调用父类的构造方法.
        FairSync(int permits) {
            super(permits);
        }

        // 尝试获取许可
        protected int tryAcquireShared(int acquires) {
            for (; ; ) {
                // 公平模式需要检测是否前面有排队的
                // 如果有排队的直接返回失败
                if (hasQueuedPredecessors())
                    return -1;

                // 没有排队的再尝试更新state的值
                int available = getState();
                int remaining = available - acquires;
                if (remaining < 0 ||
                        compareAndSetState(available, remaining))
                    return remaining;
            }
        }
    }

    // 构造方法,创建时要传入许可次数,默认使用非公平模式.
    public Semaphore(int permits) {
        sync = new NonfairSync(permits);
    }

    // 构造方法,需要传入许可次数,及是否公平模式.
    public Semaphore(int permits, boolean fair) {
        sync = fair ? new FairSync(permits) : new NonfairSync(permits);
    }

    // 获取一个许可,默认使用的是可中断方式,如果尝试获取许可失败,会进入AQS的队列中排队.
    public void acquire() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }

    // 获取一个许可,非中断方式,如果尝试获取许可失败,会进入AQS的队列中排队.
    public void acquireUninterruptibly() {
        sync.acquireShared(1);
    }

    // 尝试获取一个许可,使用Sync的非公平模式尝试获取许可方法,不论是否获取到许可都返回,只尝试一次,不会进入队列排队.
    public boolean tryAcquire() {
        return sync.nonfairTryAcquireShared(1) >= 0;
    }

    // 尝试获取一个许可,先尝试一次获取许可,如果失败则会等待timeout时间,这段时间内都没有获取到许可,则返回false,否则返回true.
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }

    // 释放一个许可,释放一个许可时state的值会加1,并且会唤醒下一个等待获取许可的线程.
    public void release() {
        sync.releaseShared(1);
    }

    // 一次获取多个许可 可中断方式
    public void acquire(int permits) throws InterruptedException {
        if (permits < 0) throw new IllegalArgumentException();
        sync.acquireSharedInterruptibly(permits);
    }

    // 一次获取多个许可 非中断方式
    public void acquireUninterruptibly(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        sync.acquireShared(permits);
    }

    // 一次尝试获取多个许可 只尝试一次
    public boolean tryAcquire(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        return sync.nonfairTryAcquireShared(permits) >= 0;
    }

    // 尝试获取多个许可,并会等待timeout时间,这段时间没获取到许可则返回false,否则返回true.
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (permits < 0) throw new IllegalArgumentException();
        return sync.tryAcquireSharedNanos(permits, unit.toNanos(timeout));
    }

    // 一次释放多个许可,state的值会相应增加permits的数量.
    public void release(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        sync.releaseShared(permits);
    }

    // 获取可用的许可次数
    public int availablePermits() {
        return sync.getPermits();
    }

    // 销毁当前可用的许可次数,对于已经获取的许可没有影响,会把当前剩余的许可全部销毁.
    public int drainPermits() {
        return sync.drainPermits();
    }

    // 减少许可的次数
    protected void reducePermits(int reduction) {
        if (reduction < 0) throw new IllegalArgumentException();
        sync.reducePermits(reduction);
    }

    public boolean isFair() {
        return sync instanceof FairSync;
    }

    // 查询是否有线程在等待获取.
    // 请注意:因为取消可能随时发生,true返回并不能保证任何其他线程将永远获得,该方法主要用于监控系统状态.
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    // 返回等待获取的线程数的估计值.该值只是一个估计值,因为当此方法遍历内部数据结构时,线程数可能会动态变化.
    // 此方法设计用于监视系统状态，而不是用于同步控制
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    // 返回一个包含可能正在等待获取的线程的集合.
    // 由于在构造此结果时实际线程集可能会动态更改,因此返回的集合只是尽力而为的估计.
    // 返回集合的元素没有特定的顺序,此方法旨在促进子类的构建,以提供更广泛的监视设施.
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    public String toString() {
        return super.toString() + "[Permits = " + sync.getPermits() + "]";
    }


}
