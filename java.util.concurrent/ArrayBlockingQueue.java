package java.util.concurrent;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.lang.ref.WeakReference;
import java.util.Spliterators;
import java.util.Spliterator;

/**
 * @param <E> the type of elements held in this collection
 * @author Doug Lea
 * @since 1.5
 */
public class ArrayBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E>, java.io.Serializable {

    private static final long serialVersionUID = -817911632652898426L;

    final Object[] items;   //队列内部实现使用数组
    int takeIndex;  //下一次从队列中获取的队列元素索引
    int putIndex;   //下一次插入队列的队列元素索引
    int count;      //队列长度

    final ReentrantLock lock;  //互斥锁

    private final Condition notEmpty;  //非空信号量
    private final Condition notFull;   //非满信号量
    transient Itrs itrs = null;  //迭代器维护列表，每次队列更新操作需要更新迭代器保证正确性

    // Internal helper methods

    final int dec(int i) {
        return ((i == 0) ? items.length : i) - 1;
    }

    //返回索引i处的元素
    @SuppressWarnings("unchecked")
    final E itemAt(int i) {
        return (E) items[i];
    }

    //Null值检查
    private static void checkNotNull(Object v) {
        if (v == null)
            throw new NullPointerException();
    }

    /**
     * 入队操作，添加元素到队列中，当且仅当持有lock的时候。
     * 每次添加完毕后通过notEmpty.signal()唤醒之前通过notEmpty.await()进入等待状态的线程
     *
     * @param x 待添加的元素
     */
    private void enqueue(E x) {
        final Object[] items = this.items;
        items[putIndex] = x;
        //达到数组最大索引时，putIndex指向数组为0的位置
        if (++putIndex == items.length)
            putIndex = 0;
        //队列长度加1
        count++;
        notEmpty.signal();
    }

    /**
     * 出队操作，获取队列元素，当且仅当持有lock的时候。
     * 每次获取完毕后通过notFull.signal()唤醒之前通过notFull.await()进入等待状态的线程
     *
     * @return 返回出队的元素
     */
    private E dequeue() {
        final Object[] items = this.items;
        @SuppressWarnings("unchecked")
        E x = (E) items[takeIndex];
        //指向置空，删除队列中的这个元素
        items[takeIndex] = null;
        //takeIndex同putIndex，达到数组最大索引时，指向数组为0的位置
        if (++takeIndex == items.length)
            takeIndex = 0;
        //队列长度减1
        count--;
        //更新迭代器中的元素数据，保证在并发操作中迭代的正确性
        if (itrs != null)
            itrs.elementDequeued();
        notFull.signal();
        return x;
    }

    /**
     * 移除指定位置的队列元素并调整队列
     */
    void removeAt(final int removeIndex) {
        final Object[] items = this.items;
        //移除队列元素索引是队列出队索引时，参考出队操作即可
        if (removeIndex == takeIndex) {
            //移除队列元素置空
            items[takeIndex] = null;
            //调整队列出队索引
            if (++takeIndex == items.length)
                takeIndex = 0;
            //队列长度减1
            count--;
            //如果有迭代器则需要更新
            if (itrs != null)
                itrs.elementDequeued();
        } else {
            //到这里表明删除的元素非队列出队索引，删除元素在队列中间
            final int putIndex = this.putIndex;
            //调整队列元素，队列删除元素后的所有元素向前移动一位
            for (int i = removeIndex; ; ) {
                int next = i + 1;
                if (next == items.length)
                    next = 0;
                if (next != putIndex) {
                    items[i] = items[next];
                    i = next;
                } else {
                    //next = putIndex 说明是putIndex前一个元素，则置空更新putIndex即可结束
                    items[i] = null;
                    this.putIndex = i;
                    break;
                }
            }
            count--;
            //同步迭代器操作
            if (itrs != null)
                itrs.removedAt(removeIndex);
        }
        //唤醒入队线程
        notFull.signal();
    }

/********************************************** 构造方法 start *********************************************************/
    /**
     * 默认使用非公平锁，创建一个互斥锁和两个Condition来帮助完成整个队列的操作，
     * 从构造方法上也可以看到，队列容量是必须要传的
     *
     * @param capacity 容量
     */
    public ArrayBlockingQueue(int capacity) {
        this(capacity, false);
    }

    public ArrayBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0)
            throw new IllegalArgumentException();
        this.items = new Object[capacity];
        lock = new ReentrantLock(fair);
        notEmpty = lock.newCondition();
        notFull = lock.newCondition();
    }

    public ArrayBlockingQueue(int capacity, boolean fair, Collection<? extends E> c) {
        this(capacity, fair);
        final ReentrantLock lock = this.lock;
        //使用锁操作确保可见性，因为item这里本身并不保证可见性，防止并发操作下线程内存中数组不一致的情况出现
        lock.lock();
        try {
            int i = 0;
            try {
                for (E e : c) {
                    checkNotNull(e);
                    items[i++] = e;
                }
            } catch (ArrayIndexOutOfBoundsException ex) {
                throw new IllegalArgumentException();
            }
            count = i;
            //队列被占满putIndex置0，从这里也能看出来这个数组是循环利用的，达到最大值，置成0，类似环状数组
            putIndex = (i == capacity) ? 0 : i;
        } finally {
            lock.unlock();
        }
    }
/********************************************** 构造方法 end ***********************************************************/

    /**
     * 实际调用offer，元素入队操作，
     * 成功则返回true,失败则抛错IllegalStateException
     */
    public boolean add(E e) {
        //调用offer获取结果 不成功则抛错
        return super.add(e);
    }

    /**
     * 如果可以在不超过队列容量的情况下立即插入指定的元素，
     * 则在队列尾部插入指定的元素，如果成功则返回true，如果队列已满则返回false。
     * 这种方法通常比方法add更可取，后者仅通过抛出异常就无法插入元素
     */
    public boolean offer(E e) {
        //检查元素是否为空
        checkNotNull(e);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            //队列已满则返回false
            if (count == items.length)
                return false;
            else {
                enqueue(e);  //入队
                return true;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在此队列的尾部插入指定的元素，如果队列已满，则等待空间可用。
     */
    public void put(E e) throws InterruptedException {
        checkNotNull(e);
        final ReentrantLock lock = this.lock;
        //lockInterruptibly方法可被中断
        lock.lockInterruptibly();
        try {
            while (count == items.length)
                //队列已满阻塞等待
                notFull.await();
            enqueue(e);  //入队
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将指定的元素插入此队列的尾部，如果队列已满，则等待指定的等待时间直到空间可用为止
     */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        checkNotNull(e);
        //阻塞等待时间 纳秒
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        //lockInterruptibly方法可被中断
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (nanos <= 0)
                    //超时返回
                    return false;
                //队列已满则阻塞等待nanos纳秒
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 出队操作，队列为空返回null,不为空则返回对应元素
     */
    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            //队列无元素返回null,有元素则出队列操作
            return (count == 0) ? null : dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 可中断，出队操作，队列为空则阻塞等待直到被通知获取值返回
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        //lockInterruptibly方法可被中断
        lock.lockInterruptibly();
        try {
            while (count == 0)
                //队列为空则阻塞等待直到被唤醒
                notEmpty.await();
            return dequeue();  //出队
        } finally {
            lock.unlock();
        }
    }

    /**
     * 可中断，出队操作，设置阻塞等待超时时间，超时则返回null,成功则返回对应元素.
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        //lockInterruptibly方法可被中断
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (nanos <= 0)
                    //超时返回
                    return null;
                //阻塞等待超时nanos纳秒
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue(); //出队
        } finally {
            lock.unlock();
        }
    }

    /**
     * 仅仅返回队列元素的值，但是不执行出队操作
     */
    public E peek() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            //返回队列takeIndex索引处的值，不进行出队列操作，元素不会被删除
            return itemAt(takeIndex); // null when queue is empty
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回此队列中的元素数量
     */
    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 剩余容量
     */
    public int remainingCapacity() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return items.length - count; //数组长度-数组元素数量
        } finally {
            lock.unlock();
        }
    }

    /**
     * 删除队列中的对应元素（可处于队列任何位置），队列需进行整理
     */
    public boolean remove(Object o) {
        if (o == null) return false;
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count > 0) {
                final int putIndex = this.putIndex; //入队索引
                int i = takeIndex;  //出队索引
                do {
                    if (o.equals(items[i])) {
                        //移除i处的元素，同时队列进行整理，移除元素后的元素依次向前移动进行填补空缺
                        removeAt(i);
                        return true;
                    }
                    //循环到数组长度，从0继续
                    if (++i == items.length)
                        i = 0;
                } while (i != putIndex);
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 如果此队列包含指定的元素，则返回true。
     */
    public boolean contains(Object o) {
        if (o == null) return false;
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count > 0) {
                final int putIndex = this.putIndex;
                int i = takeIndex;
                do {
                    if (o.equals(items[i]))
                        return true;
                    if (++i == items.length)
                        i = 0;
                } while (i != putIndex);
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回一个数组，该数组以适当的顺序包含此队列中的所有元素。
     * 返回的数组是“安全的”，因为这个队列不维护对它的引用。
     * (换句话说，这个方法必须分配一个新的数组)。因此，调用者可以自由地修改返回的数组。
     */
    public Object[] toArray() {
        Object[] a;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final int count = this.count;
            a = new Object[count];
            int n = items.length - takeIndex;
            if (count <= n)
                System.arraycopy(items, takeIndex, a, 0, count);
            else {
                System.arraycopy(items, takeIndex, a, 0, n);
                System.arraycopy(items, 0, a, n, count - n);
            }
        } finally {
            lock.unlock();
        }
        return a;
    }

    /**
     * 返回一个数组，该数组包含此队列中的所有元素，并以适当的顺序;
     * 返回数组的运行时类型是指定数组的运行时类型。
     * 如果队列符合指定的数组，则返回其中。否则，将使用指定数组的运行时类型和此队列的大小分配新数组。
     * 如果这个队列在指定的数组中有多余的空间(例如，数组比这个队列有更多的元素)，紧接在队列末尾的数组中的元素被设置为null。
     * 与toArray()方法类似，该方法充当基于数组和基于集合的api之间的桥梁。
     * 此外，这种方法允许精确控制输出数组的运行时类型，并且在某些情况下，可以用来节省分配成本。
     * 假设x是一个已知仅包含字符串的队列。下面的代码可以用来将队列转储到一个新分配的字符串数组中:
     * String[] y = x.toArray(new String[0]); 注意toArray(新对象[0])在函数上与toArray()相同
     */
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final int count = this.count;
            final int len = a.length;
            if (len < count)
                a = (T[]) java.lang.reflect.Array.newInstance(
                        a.getClass().getComponentType(), count);
            int n = items.length - takeIndex;
            if (count <= n)
                System.arraycopy(items, takeIndex, a, 0, count);
            else {
                System.arraycopy(items, takeIndex, a, 0, n);
                System.arraycopy(items, 0, a, n, count - n);
            }
            if (len > count)
                a[count] = null;
        } finally {
            lock.unlock();
        }
        return a;
    }

    public String toString() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int k = count;
            if (k == 0)
                return "[]";
            final Object[] items = this.items;
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = takeIndex; ; ) {
                Object e = items[i];
                sb.append(e == this ? "(this Collection)" : e);
                if (--k == 0)
                    return sb.append(']').toString();
                sb.append(',').append(' ');
                if (++i == items.length)
                    i = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 原子地从队列中删除所有元素。在此调用返回后，队列将为空
     */
    public void clear() {
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int k = count;
            if (k > 0) {
                final int putIndex = this.putIndex;
                int i = takeIndex;
                do {
                    items[i] = null;
                    if (++i == items.length)
                        i = 0;
                } while (i != putIndex);
                takeIndex = putIndex;
                count = 0;
                if (itrs != null)
                    itrs.queueIsEmpty();
                for (; k > 0 && lock.hasWaiters(notFull); k--)
                    notFull.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        checkNotNull(c);
        if (c == this)
            throw new IllegalArgumentException();
        if (maxElements <= 0)
            return 0;
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = Math.min(maxElements, count);
            int take = takeIndex;
            int i = 0;
            try {
                while (i < n) {
                    @SuppressWarnings("unchecked")
                    E x = (E) items[take];
                    c.add(x);
                    items[take] = null;
                    if (++take == items.length)
                        take = 0;
                    i++;
                }
                return n;
            } finally {
                // Restore invariants even if c.add() threw
                if (i > 0) {
                    count -= i;
                    takeIndex = take;
                    if (itrs != null) {
                        if (count == 0)
                            itrs.queueIsEmpty();
                        else if (i > take)
                            itrs.takeIndexWrapped();
                    }
                    for (; i > 0 && lock.hasWaiters(notFull); i--)
                        notFull.signal();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an iterator over the elements in this queue in proper sequence.
     * The elements will be returned in order from first (head) to last (tail).
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue in proper sequence
     */
    public Iterator<E> iterator() {
        return new Itr();
    }

    /**
     * Shared data between iterators and their queue, allowing queue
     * modifications to update iterators when elements are removed.
     *
     * This adds a lot of complexity for the sake of correctly
     * handling some uncommon operations, but the combination of
     * circular-arrays and supporting interior removes (i.e., those
     * not at head) would cause iterators to sometimes lose their
     * places and/or (re)report elements they shouldn't.  To avoid
     * this, when a queue has one or more iterators, it keeps iterator
     * state consistent by:
     *
     * (1) keeping track of the number of "cycles", that is, the
     * number of times takeIndex has wrapped around to 0.
     * (2) notifying all iterators via the callback removedAt whenever
     * an interior element is removed (and thus other elements may
     * be shifted).
     *
     * These suffice to eliminate iterator inconsistencies, but
     * unfortunately add the secondary responsibility of maintaining
     * the list of iterators.  We track all active iterators in a
     * simple linked list (accessed only when the queue's lock is
     * held) of weak references to Itr.  The list is cleaned up using
     * 3 different mechanisms:
     *
     * (1) Whenever a new iterator is created, do some O(1) checking for
     * stale list elements.
     *
     * (2) Whenever takeIndex wraps around to 0, check for iterators
     * that have been unused for more than one wrap-around cycle.
     *
     * (3) Whenever the queue becomes empty, all iterators are notified
     * and this entire data structure is discarded.
     *
     * So in addition to the removedAt callback that is necessary for
     * correctness, iterators have the shutdown and takeIndexWrapped
     * callbacks that help remove stale iterators from the list.
     *
     * Whenever a list element is examined, it is expunged if either
     * the GC has determined that the iterator is discarded, or if the
     * iterator reports that it is "detached" (does not need any
     * further state updates).  Overhead is maximal when takeIndex
     * never advances, iterators are discarded before they are
     * exhausted, and all removals are interior removes, in which case
     * all stale iterators are discovered by the GC.  But even in this
     * case we don't increase the amortized complexity.
     *
     * Care must be taken to keep list sweeping methods from
     * reentrantly invoking another such method, causing subtle
     * corruption bugs.
     */
    class Itrs {

        /**
         * Node in a linked list of weak iterator references.
         */
        private class Node extends WeakReference<Itr> {
            Node next;

            Node(Itr iterator, Node next) {
                super(iterator);
                this.next = next;
            }
        }

        /**
         * Incremented whenever takeIndex wraps around to 0
         */
        int cycles = 0;

        /**
         * Linked list of weak iterator references
         */
        private Node head;

        /**
         * Used to expunge stale iterators
         */
        private Node sweeper = null;

        private static final int SHORT_SWEEP_PROBES = 4;
        private static final int LONG_SWEEP_PROBES = 16;

        Itrs(Itr initial) {
            register(initial);
        }

        /**
         * Sweeps itrs, looking for and expunging stale iterators.
         * If at least one was found, tries harder to find more.
         * Called only from iterating thread.
         *
         * @param tryHarder whether to start in try-harder mode, because
         *                  there is known to be at least one iterator to collect
         */
        void doSomeSweeping(boolean tryHarder) {
            // assert lock.getHoldCount() == 1;
            // assert head != null;
            int probes = tryHarder ? LONG_SWEEP_PROBES : SHORT_SWEEP_PROBES;
            Node o, p;
            final Node sweeper = this.sweeper;
            boolean passedGo;   // to limit search to one full sweep

            if (sweeper == null) {
                o = null;
                p = head;
                passedGo = true;
            } else {
                o = sweeper;
                p = o.next;
                passedGo = false;
            }

            for (; probes > 0; probes--) {
                if (p == null) {
                    if (passedGo)
                        break;
                    o = null;
                    p = head;
                    passedGo = true;
                }
                final Itr it = p.get();
                final Node next = p.next;
                if (it == null || it.isDetached()) {
                    // found a discarded/exhausted iterator
                    probes = LONG_SWEEP_PROBES; // "try harder"
                    // unlink p
                    p.clear();
                    p.next = null;
                    if (o == null) {
                        head = next;
                        if (next == null) {
                            // We've run out of iterators to track; retire
                            itrs = null;
                            return;
                        }
                    } else
                        o.next = next;
                } else {
                    o = p;
                }
                p = next;
            }

            this.sweeper = (p == null) ? null : o;
        }

        /**
         * Adds a new iterator to the linked list of tracked iterators.
         */
        void register(Itr itr) {
            // assert lock.getHoldCount() == 1;
            head = new Node(itr, head);
        }

        /**
         * Called whenever takeIndex wraps around to 0.
         *
         * Notifies all iterators, and expunges any that are now stale.
         */
        void takeIndexWrapped() {
            // assert lock.getHoldCount() == 1;
            cycles++;
            for (Node o = null, p = head; p != null; ) {
                final Itr it = p.get();
                final Node next = p.next;
                if (it == null || it.takeIndexWrapped()) {
                    // unlink p
                    // assert it == null || it.isDetached();
                    p.clear();
                    p.next = null;
                    if (o == null)
                        head = next;
                    else
                        o.next = next;
                } else {
                    o = p;
                }
                p = next;
            }
            if (head == null)   // no more iterators to track
                itrs = null;
        }

        /**
         * Called whenever an interior remove (not at takeIndex) occurred.
         *
         * Notifies all iterators, and expunges any that are now stale.
         */
        void removedAt(int removedIndex) {
            for (Node o = null, p = head; p != null; ) {
                final Itr it = p.get();
                final Node next = p.next;
                if (it == null || it.removedAt(removedIndex)) {
                    // unlink p
                    // assert it == null || it.isDetached();
                    p.clear();
                    p.next = null;
                    if (o == null)
                        head = next;
                    else
                        o.next = next;
                } else {
                    o = p;
                }
                p = next;
            }
            if (head == null)   // no more iterators to track
                itrs = null;
        }

        /**
         * Called whenever the queue becomes empty.
         *
         * Notifies all active iterators that the queue is empty,
         * clears all weak refs, and unlinks the itrs datastructure.
         */
        void queueIsEmpty() {
            // assert lock.getHoldCount() == 1;
            for (Node p = head; p != null; p = p.next) {
                Itr it = p.get();
                if (it != null) {
                    p.clear();
                    it.shutdown();
                }
            }
            head = null;
            itrs = null;
        }

        /**
         * Called whenever an element has been dequeued (at takeIndex).
         */
        void elementDequeued() {
            // assert lock.getHoldCount() == 1;
            if (count == 0)
                queueIsEmpty();
            else if (takeIndex == 0)
                takeIndexWrapped();
        }
    }

    private class Itr implements Iterator<E> {
        private int cursor; //指向下一个迭代元素的游标，结束时为NONE（-1）
        private E nextItem; //下次调用next()返回的元素，无则返回null
        private int nextIndex;  //下一个元素nextItem的索引值，空的话返回NONE（-1），如果被移除了则返回REMOVED（-2）
        private E lastItem; //上一次调用next()返回的元素，无则返回null
        private int lastRet;  //上一个元素的索引值，空的话返回NONE，如果被移除了则返回REMOVED
        private int prevTakeIndex; //上一个takeIndex对应的值，当处于无效状态时为DETACHED（-3），以这个变量标识迭代器DETACHED状态
        private int prevCycles;  //上一个cycles的值
        private static final int NONE = -1; //标识不可用值或未定义值
        private static final int REMOVED = -2;  // 标识元素被移除
        private static final int DETACHED = -3;  // 标识prevTakeIndex为无效状态

        Itr() {
            // 构造时上一个元素索引为空
            lastRet = NONE;
            // 使用互斥锁
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                // 队列为空，初始化变量
                if (count == 0) {
                    cursor = NONE;
                    nextIndex = NONE;
                    // 无效状态的迭代器
                    prevTakeIndex = DETACHED;
                } else {
                    // 此时队列不为空
                    // 记录出队的takeIndex
                    final int takeIndex = ArrayBlockingQueue.this.takeIndex;
                    prevTakeIndex = takeIndex;
                    // 下一次使用迭代返回的值，这里就已经保存好了调用next的返回值
                    nextItem = itemAt(nextIndex = takeIndex);
                    // 游标指向takeIndex+1
                    cursor = incCursor(takeIndex);
                    // 使用Itrs来维护所有的迭代器
                    if (itrs == null) {
                        // 空则创建
                        itrs = new Itrs(this);
                    } else {
                        // 非空将次迭代器注册
                        itrs.register(this);
                        // 清理迭代器，每次创建新迭代器时都会进行一次简单的清理操作
                        itrs.doSomeSweeping(false);
                    }
                    // 保存队列循环的次数，cycles在itrs进行解释
                    prevCycles = itrs.cycles;
                }
            } finally {
                lock.unlock();
            }
        }

        //判断迭代器是否已经无效，通过prevTakeIndex来判断
        boolean isDetached() {
            // assert lock.getHoldCount() == 1;
            return prevTakeIndex < 0;
        }

        // 游标值+1
        private int incCursor(int index) {
            // assert lock.getHoldCount() == 1;
            if (++index == items.length) // 达到最大值从0开始
                index = 0;
            // 与下一个入队元素位置相同，则表示队列已无元素，置NONE
            if (index == putIndex)
                index = NONE;
            return index;
        }

        /**
         * 从prevTakeIndex开始，队列索引index无效则返回true
         * 在incorporateDequeues中使用，比较index，prevTakeIndex的距离与实际距离
         */
        private boolean invalidated(int index, int prevTakeIndex, long dequeues, int length) {
            // 初始化时设置小于0
            if (index < 0)
                return false;
            // 当前index与prevTakeIndex的距离
            int distance = index - prevTakeIndex;
            // 发生循环操作，很好理解，加上数组length，即为正确的距离
            if (distance < 0)
                distance += length;
            // 如果distance小于实际距离，则无效返回true
            return dequeues > distance;
        }

        /**
         * 迭代操作后元素出队调整索引
         */
        private void incorporateDequeues() {
            // 获取当前变量
            final int cycles = itrs.cycles;
            final int takeIndex = ArrayBlockingQueue.this.takeIndex;
            final int prevCycles = this.prevCycles;
            final int prevTakeIndex = this.prevTakeIndex;
            // cycles != prevCycles 表示队列已经被循环使用过，相当于循环到0重新入队出队
            // takeIndex != prevTakeIndex 表示队列元素有出队，需重新排序
            if (cycles != prevCycles || takeIndex != prevTakeIndex) {
                final int len = items.length;
                // 队列实际移动的长度
                long dequeues = (cycles - prevCycles) * len + (takeIndex - prevTakeIndex);
                // lastRet处元素被移除了
                if (invalidated(lastRet, prevTakeIndex, dequeues, len))
                    lastRet = REMOVED;
                // nextIndex处元素被移除了
                if (invalidated(nextIndex, prevTakeIndex, dequeues, len))
                    nextIndex = REMOVED;
                // 游标索引无效置为当前队列takeIndex
                if (invalidated(cursor, prevTakeIndex, dequeues, len))
                    cursor = takeIndex;

                // 迭代器无效进行清理操作
                if (cursor < 0 && nextIndex < 0 && lastRet < 0)
                    detach();
                else {
                    // 更新索引
                    this.prevCycles = cycles;
                    this.prevTakeIndex = takeIndex;
                }
            }
        }

        /**
         * Called when itrs should stop tracking this iterator, either
         * because there are no more indices to update (cursor < 0 &&
         * nextIndex < 0 && lastRet < 0) or as a special exception, when
         * lastRet >= 0, because hasNext() is about to return false for the
         * first time.  Call only from iterating thread.
         */
        private void detach() {
            if (prevTakeIndex >= 0) {
                // 设置迭代器DETACHED状态，无效状态
                prevTakeIndex = DETACHED;
                // 所有的迭代器进行一次清理
                itrs.doSomeSweeping(true);
            }
        }

        /**
         * 出于对性能的考虑，这里不会进行加锁
         */
        public boolean hasNext() {
            // 直接判断nextItem，故首次初始化时后续清空队列这里nextItem不为空，会返回第一个队列值
            if (nextItem != null)
                return true;
            // nextItem为空时才会进入noNext
            noNext();
            return false;
        }

        /**
         * 无元素，清理
         */
        private void noNext() {
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                // prevTakeIndex >= 0，需要进行处理
                if (!isDetached()) {
                    incorporateDequeues();
                    if (lastRet >= 0) {
                        // 保存lastItem值，remove方法中需要用到
                        lastItem = itemAt(lastRet);
                        detach();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        public E next() {
            // 迭代返回值，每次调用next前已经确定了nextItem值
            final E x = nextItem;
            if (x == null)
                throw new NoSuchElementException();
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                if (!isDetached())
                    incorporateDequeues();
                // next调用之后调用remove删除元素的时候使用
                lastRet = nextIndex;
                final int cursor = this.cursor;
                if (cursor >= 0) {
                    // 下一次迭代返回值
                    nextItem = itemAt(nextIndex = cursor);
                    // 游标加1
                    this.cursor = incCursor(cursor);
                } else {
                    // 无下一个迭代元素
                    nextIndex = NONE;
                    nextItem = null;
                }
            } finally {
                lock.unlock();
            }
            return x;
        }

        public void remove() {
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                if (!isDetached())
                    incorporateDequeues();
                final int lastRet = this.lastRet;
                this.lastRet = NONE;
                // 删除时需要用到lastRet
                if (lastRet >= 0) {
                    if (!isDetached())
                        removeAt(lastRet);
                    else {
                        // 处理在hasNext()返回false之后调用iterator .remove()的特殊情况
                        final E lastItem = this.lastItem;
                        this.lastItem = null;
                        // 和预期元素一致才进行删除
                        if (itemAt(lastRet) == lastItem)
                            removeAt(lastRet);
                    }
                } else if (lastRet == NONE)
                    // lastRet为NONE时会抛错
                    throw new IllegalStateException();
                if (cursor < 0 && nextIndex < 0)
                    detach();
            } finally {
                lock.unlock();
            }
        }

        /**
         * 队列为空或者无效时应清理掉，更新内部变量值
         */
        void shutdown() {
            cursor = NONE;
            if (nextIndex >= 0)
                nextIndex = REMOVED;
            if (lastRet >= 0) {
                lastRet = REMOVED;
                lastItem = null;
            }
            prevTakeIndex = DETACHED;
        }

        /**
         * 计算index与prevTakeIndex的距离
         */
        private int distance(int index, int prevTakeIndex, int length) {
            int distance = index - prevTakeIndex;
            if (distance < 0)
                distance += length;
            return distance;
        }

        /**
         * 队列内部元素被删除时调用，保证迭代器的正确性
         */
        boolean removedAt(int removedIndex) {
            // 当前迭代器无效时直接返回true
            if (isDetached())
                return true;
            final int cycles = itrs.cycles;
            final int takeIndex = ArrayBlockingQueue.this.takeIndex;
            final int prevCycles = this.prevCycles;
            final int prevTakeIndex = this.prevTakeIndex;
            final int len = items.length;
            int cycleDiff = cycles - prevCycles;
            // 删除的索引位置小于takeIndex，循环次数+1
            if (removedIndex < takeIndex)
                cycleDiff++;
            // 删除元素的实际距离
            final int removedDistance =
                    (cycleDiff * len) + (removedIndex - prevTakeIndex);
            int cursor = this.cursor;
            if (cursor >= 0) {
                int x = distance(cursor, prevTakeIndex, len);
                // 游标指向被删除的元素位置
                if (x == removedDistance) {
                    // 已经没有元素了，置NONE
                    if (cursor == putIndex)
                        this.cursor = cursor = NONE;
                    // 游标已经超过removedDistance，索引游标需-1，因为删除元素算在了游标内，
                    // 需减1才能保证队列删除元素之后整体元素位置调整之后迭代器这里的正确性
                } else if (x > removedDistance) {
                    this.cursor = cursor = dec(cursor);
                }
            }
            int lastRet = this.lastRet;
            // lastRet同上
            if (lastRet >= 0) {
                int x = distance(lastRet, prevTakeIndex, len);
                if (x == removedDistance)
                    this.lastRet = lastRet = REMOVED;
                else if (x > removedDistance)
                    this.lastRet = lastRet = dec(lastRet);
            }
            int nextIndex = this.nextIndex;
            // nextIndex同上
            if (nextIndex >= 0) {
                int x = distance(nextIndex, prevTakeIndex, len);
                if (x == removedDistance)
                    this.nextIndex = nextIndex = REMOVED;
                else if (x > removedDistance)
                    this.nextIndex = nextIndex = dec(nextIndex);
                // 迭代器无效状态
            } else if (cursor < 0 && nextIndex < 0 && lastRet < 0) {
                this.prevTakeIndex = DETACHED;
                return true;
            }
            return false;
        }

        /**
         * 每当takeIndex循环到0时调用
         */
        boolean takeIndexWrapped() {
            if (isDetached())
                return true;
            if (itrs.cycles - prevCycles > 1) {
                // 迭代器所有元素都已经在入队出队中不存在了，需置迭代器无效，这里也能看到相差2的时候已经无效了
                shutdown();
                return true;
            }
            return false;
        }
    }

    /**
     * Returns a {@link Spliterator} over the elements in this queue.
     *
     * <p>The returned spliterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The {@code Spliterator} reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#ORDERED}, and {@link Spliterator#NONNULL}.
     *
     * @return a {@code Spliterator} over the elements in this queue
     * @implNote The {@code Spliterator} implements {@code trySplit} to permit limited
     * parallelism.
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return Spliterators.spliterator
                (this, Spliterator.ORDERED | Spliterator.NONNULL |
                        Spliterator.CONCURRENT);
    }

    /**
     * Deserializes this queue and then checks some invariants.
     *
     * @param s the input stream
     * @throws ClassNotFoundException         if the class of a serialized object
     *                                        could not be found
     * @throws java.io.InvalidObjectException if invariants are violated
     * @throws java.io.IOException            if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {

        // Read in items array and various fields
        s.defaultReadObject();

        // Check invariants over count and index fields. Note that
        // if putIndex==takeIndex, count can be either 0 or items.length.
        if (items.length == 0 ||
                takeIndex < 0 || takeIndex >= items.length ||
                putIndex < 0 || putIndex >= items.length ||
                count < 0 || count > items.length ||
                Math.floorMod(putIndex - takeIndex, items.length) !=
                        Math.floorMod(count, items.length)) {
            throw new java.io.InvalidObjectException("invariants violated");
        }
    }
}
