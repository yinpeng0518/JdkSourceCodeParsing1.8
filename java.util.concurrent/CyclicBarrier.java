package java.util.concurrent;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Doug Lea
 * @since 1.5
 */
public class CyclicBarrier {

    // 静态内部类,该类的对象代表栅栏的当前代,就像玩游戏时代表的本局游戏,利用它可以实现循环等待.
    private static class Generation {
        Generation() {
        }

        boolean broken; // 当前代屏障是否破坏标识
    }

    // 同步操作锁
    private final ReentrantLock lock = new ReentrantLock();
    // 线程拦截器
    private final Condition trip = lock.newCondition();
    // 每次拦截的线程数，在构造时进行赋值
    private final int parties;
    // 换代前执行的任务，当count减为0时表示本局游戏结束，需要转到下一局。
    // 在转到下一局游戏之前会将所有阻塞的线程唤醒，在唤醒所有线程之前你可以通过指定barrierCommand来执行自己的任务
    // 由最后一个到达栅栏的线程执行,如果没有需要执行的,传null.
    private final Runnable barrierCommand;
    // 静态内部类,该类的对象代表栅栏的当前代,就像玩游戏时代表的本局游戏,利用它可以实现循环等待.
    private Generation generation = new Generation();
    // 内部计数器,它的初始值和parties相同,以后随着每次await方法的调用而减1,直到减为0就将所有线程唤醒.
    private int count;

    // 唤醒所有线程并转到下一代,仅在持有锁时调用
    private void nextGeneration() {
        trip.signalAll();               // 唤醒条件队列所有线程
        count = parties;                // 设置计数器的值为需要拦截的线程数
        generation = new Generation();  // 重新设置栅栏代次
    }

    // 将当前屏障生成设置为已破坏并唤醒所有线程,仅在持有锁时调用.
    private void breakBarrier() {
        generation.broken = true;  // 将当前栅栏状态设置为打翻
        count = parties;           // 设置计数器的值为需要拦截的线程数
        trip.signalAll();          // 唤醒所有线程
    }

    // 定时等待
    private int dowait(boolean timed, long nanos) throws InterruptedException, BrokenBarrierException, TimeoutException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Generation g = generation;
            // 检查当前栅栏是否被破坏
            if (g.broken)
                throw new BrokenBarrierException();

            // 检查当前线程是否被中断
            if (Thread.interrupted()) {
                // 如果当前线程被中断会做以下三件事:
                // 1.破坏当前栅栏
                // 2.唤醒拦截的所有线程
                // 3.抛出中断异常
                breakBarrier();
                throw new InterruptedException();
            }
            // 每次都将计数器的值减1
            int index = --count;
            // 计数器的值减为0则需唤醒所有线程并转换到下一代
            if (index == 0) {  // tripped
                boolean ranAction = false;
                try {
                    // 唤醒所有线程前先执行指定的任务
                    final Runnable command = barrierCommand;
                    if (command != null)
                        command.run();
                    ranAction = true;
                    // 唤醒所有线程并转到下一代
                    nextGeneration();
                    return 0;
                } finally {
                    // 确保在任务未成功执行时能将所有线程唤醒
                    if (!ranAction)
                        breakBarrier();
                }
            }

            // 如果计数器不为0则执行此循环
            for (; ; ) {
                try {
                    // 根据传入的参数来决定是定时等待还是非定时等待
                    if (!timed)
                        trip.await();
                    else if (nanos > 0L)
                        nanos = trip.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    // 若当前线程在等待期间被中断则破坏栅栏唤醒其他线程
                    if (g == generation && !g.broken) {
                        breakBarrier();
                        throw ie;
                    } else {
                        // 若在捕获中断异常前已经完成在栅栏上的等待, 则直接调用中断操作
                        Thread.currentThread().interrupt();
                    }
                }

                // 如果线程因为破坏栅栏操作而被唤醒则抛出异常
                if (g.broken)
                    throw new BrokenBarrierException();

                // 如果线程因为换代操作而被唤醒则返回计数器的值
                if (g != generation)
                    return index;

                // 如果线程因为时间到了而被唤醒则破坏栅栏并抛出异常
                if (timed && nanos <= 0L) {
                    breakBarrier();
                    throw new TimeoutException();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // 创建一个新的CyclicBarrier ，它将在给定数量的线程等待时触发，并在屏障触发时执行给定的屏障操作，由进入屏障的最后一个线程执行。
    public CyclicBarrier(int parties, Runnable barrierAction) {
        if (parties <= 0) throw new IllegalArgumentException();
        this.parties = parties;
        this.count = parties;
        this.barrierCommand = barrierAction;
    }

    public CyclicBarrier(int parties) {
        this(parties, null);
    }


    // 返回触发此障碍所需的线程数量
    public int getParties() {
        return parties;
    }

    // 非定时等待
    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return dowait(false, 0L);
        } catch (TimeoutException toe) {
            throw new Error(toe); // cannot happen
        }
    }

    // 定时等待
    public int await(long timeout, TimeUnit unit) throws InterruptedException, BrokenBarrierException, TimeoutException {
        return dowait(true, unit.toNanos(timeout));
    }

    // 查询此屏障是否处于破坏状态
    public boolean isBroken() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return generation.broken;
        } finally {
            lock.unlock();
        }
    }

    // 重置一个屏障
    public void reset() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            breakBarrier();   // break the current generation
            nextGeneration(); // start a new generation
        } finally {
            lock.unlock();
        }
    }

    // 返回当前在屏障处等待的线程数量,此方法主要用于调试和断言.
    public int getNumberWaiting() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return parties - count;
        } finally {
            lock.unlock();
        }
    }
}
