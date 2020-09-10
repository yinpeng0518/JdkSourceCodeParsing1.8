package java.util.concurrent;

import java.util.Collection;
import java.util.Queue;

/**
 * 阻塞队列接口
 * 
 * ArrayBlockingQueue: 
 *     是基于数组的阻塞队列实现，在 ArrayBlockingQueue 内部，维护一个定长数组，以便缓存队列中的数据对象，内部没有实现读写分离，
 *     也就意味着生产者和消费者不能完全并行，长度是需要定义的，可以指定先进先出，或者先进后出，也叫有界队列.
 * 
 * LinkedBolckingQueue:
 *      基于链表实现的阻塞队列，和 ArrayBlockingQueue 差不多，内部也维持着一个数据缓冲队列（该队列由链表构成），
 *      LinkedBolckingQueue 之所以能高效的处理并发数据，是因为其内部采用分离锁（读写分离两个锁），从而实现生产者和消费者操作的完全并行。它是一个无界队列
 * 
 * SynchronousQueue:
 *      是一个没有缓冲的队列，生产者产生的数据直接会被消费者获取并且消费
 * 
 * PriorityBlockingQueue:
 *      基于优先级的阻塞队列（优先级的判断通过构造函数的Compator对象决定，也就是说传入队列的对象必须实现Comparable接口），内部控制线程同步的锁采用的是公平锁，它也是一个无界队列
 * 
 * DelayQueue:
 *      带有延迟时间的 Queue, 其中的元素只有当其指定的延迟时间到了，才能从队列中获取到该元素，
 *      elayQueue 中的元素必须实现 Delayed接口，DelayQueue是一个没有大小限制的队列，应用场景有很多，比如对缓存超时的数据进行移除，任务超时处理，空闲连接的关闭。
 * 
 * @since 1.5
 * @author Doug Lea
 * @param <E> the type of elements held in this collection
 */
public interface BlockingQueue<E> extends Queue<E> {
    /**
     * 如果可以在不违反容量限制的情况下立即将指定的元素插入此队列，成功时返回true，
     * 如果当前没有可用空间，则抛出IllegalStateException异常。当使用容量限制队列时，通常使用offer更好。
     *
     * @param e 要添加的元素
     * @return true(由Collection.add指定)
     * @throws IllegalStateException 如果此时由于容量限制无法添加元素
     * @throws ClassCastException 如果指定元素的类阻止它被添加到队列中
     * @throws NullPointerException 如果指定的元素为null
     * @throws IllegalArgumentException 如果指定元素的某些属性阻止它被添加到这个队列中
     */
    boolean add(E e);

    // 添加，满时返回false
    boolean offer(E e);

    //插入元素e至队尾，如果队列已满，则阻塞调用线程直到队列有空闲空间
    void put(E e) throws InterruptedException;

    //添加，满时阻塞，超时返回false
    boolean offer(E e, long timeout, TimeUnit unit)
        throws InterruptedException;

    // 出队，空时阻塞
    E take() throws InterruptedException;

    // 出队，空时阻塞，超时返回null
    E poll(long timeout, TimeUnit unit) throws InterruptedException;

    // 剩余容量（理想情况下）
    int remainingCapacity();

    //如果指定元素存在，则从此队列中删除指定元素的单个实例。
    boolean remove(Object o);

    // 是否包含
    public boolean contains(Object o);

    // 从该队列中移除所有可用元素，并将它们添加到给定集合中
    int drainTo(Collection<? super E> c);

    // 从该队列中移除最多给定数量的可用元素，并将它们添加到给定集合中。
    int drainTo(Collection<? super E> c, int maxElements);
}
