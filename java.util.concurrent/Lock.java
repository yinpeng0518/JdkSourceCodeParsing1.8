package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;

/**
 * 锁是用来控制多个线程访问共享资源的方式，一般来说，一个锁能够防止多个线程同时 访问共享资源（但是有些锁可以允许多个线程并发的访问共享资源，比如读写锁）。
 * 在Lock接 口出现之前，Java程序是靠synchronized关键字实现锁功能的，而Java SE 5之后，并发包中新增 了Lock接口（以及相关实现类）用来实现锁功能，
 * 它提供了与synchronized关键字类似的同步功 能，只是在使用时需要显式地获取和释放锁。
 * 虽然它缺少了（通过synchronized块或者方法所提 供的）隐式获取释放锁的便捷性，
 * 但是却拥有了锁获取与释放的可操作性、可中断的获取锁以及超时获取锁等多种synchronized关键字所不具备的同步特性。
 *
 * 使用synchronized关键字将会隐式地获取锁，但是它将锁的获取和释放固化了，也就是先 获取再释放。
 * 当然，这种方式简化了同步的管理，可是扩展性没有显示的锁获取和释放来的好。
 * 例如，针对一个场景，手把手进行锁获取和释放，先获得锁A，然后再获取锁B，当锁B获得后，释放锁A同时获取锁C，当锁C获得后，再释放B同时获取锁D，
 * 以此类推。这种场景下， synchronized关键字就不那么容易实现了，而使用Lock却容易许多
 *
 * @author Doug Lea
 * @see ReentrantLock
 * @see Condition
 * @see ReadWriteLock
 * @since 1.5
 */
public interface Lock {

    /**
     * 在finally块中释放锁，目的是保证在获取到锁之后，最终能够被释放。
     * 不要将获取锁的过程写在try块中，因为如果在获取锁（自定义锁的实现）时发生了异常， 异常抛出的同时，也会导致锁无故释放。
     */

    // Lock lock = new ReentrantLock();
    // lock.lock();
    // try{
    //
    // } finally{
    //  lock.unlock();
    // }

    // 阻塞获取锁,调用该方法当前线程将会获取锁,当锁获得后,从该方法返回
    void lock();

    /**
     * 获取锁，除非当前线程被中断。如果锁可用，则获取锁并立即返回。
     *
     * 如果锁不可用，那么当前线程将出于线程调度的目的被禁用，并处于休眠状态，直到发生以下两种情况之一:
     *
     * 1.锁被当前线程获取
     * 2.当前线程被其他线程打断（支持锁获取的中断）。
     */
    void lockInterruptibly() throws InterruptedException;

    boolean tryLock();

    boolean tryLock(long time, TimeUnit unit) throws InterruptedException;

    void unlock();

    Condition newCondition();
}
