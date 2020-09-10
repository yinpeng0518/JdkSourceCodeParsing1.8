package java.util.concurrent;
import java.util.*;

/**
 * 阻塞的双端队列
 * 
 * @since 1.6
 * @author Doug Lea
 * @param <E> the type of elements held in this collection
 */
public interface BlockingDeque<E> extends BlockingQueue<E>, Deque<E> {
    
    // 头部添加，满时抛出异常
    void addFirst(E e);

    // 尾部添加，满时抛出异常
    void addLast(E e);

    // 头部添加，满时返回false
    boolean offerFirst(E e);

    // 尾部添加,满时返回false
    boolean offerLast(E e);

    // 头部添加，满时阻塞
    void putFirst(E e) throws InterruptedException;

    // 尾部添加，满时阻塞
    void putLast(E e) throws InterruptedException;

    // 头部添加，满时阻塞，超时返回false
    boolean offerFirst(E e, long timeout, TimeUnit unit) throws InterruptedException;

    // 尾部添加，满时阻塞，超时返回false
    boolean offerLast(E e, long timeout, TimeUnit unit) throws InterruptedException;

    // 头部出队，空时阻塞
    E takeFirst() throws InterruptedException;

    // 尾部出队，空时阻塞
    E takeLast() throws InterruptedException;

    // 头部出队，空时阻塞，超时返回null
    E pollFirst(long timeout, TimeUnit unit) throws InterruptedException;

    // 尾部出队，空时阻塞，超时返回null
    E pollLast(long timeout, TimeUnit unit) throws InterruptedException;

    // 从头部删除元素
    boolean removeFirstOccurrence(Object o);

    // 从尾部删除元素
    boolean removeLastOccurrence(Object o);

    // *** BlockingQueue methods ***

    // 添加，满时抛异常
    boolean add(E e);

    // 添加，满时返回false
    boolean offer(E e);

    // 添加，满时阻塞
    void put(E e) throws InterruptedException;

    // 添加，满时阻塞，超时返回false
    boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException;

    // 出队，空时抛异常
    E remove();

    // 出队，空时返回null
    E poll();

    // 出队，空时阻塞
    E take() throws InterruptedException;

    // 出队，空时阻塞，超时返回null
    E poll(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * 检索但不删除由该deque表示的队列的头(换句话说，该deque的第一个元素)。
     * 这个方法与peek的不同之处在于，如果这个deque是空的，它会抛出一个异常。 这个方法等同于getFirst。
     */
    E element();

    //检索但不删除由该deque表示的队列的头(换句话说，该deque的第一个元素)，如果该deque为空，则返回null。 这种方法相当于peekFirst。
    E peek();

    //删除指定元素的第一个匹配项。如果deque不包含该元素，则该元素不变。这个方法相当于removeFirstOccurrence。如果该deque因调用而改变，则为true
    boolean remove(Object o);

    //如果该deque包含指定元素，则返回true。
    public boolean contains(Object o);

    //返回该deque中的元素数量
    public int size();

    // 迭代器
    Iterator<E> iterator();

    // *** Stack methods ***

    /**
     * 如果可以在不违反容量限制的情况下立即将元素压入此deque表示的堆栈(换句话说，在此deque的顶部)，如果当前没有可用空间，则抛出IllegalStateException。 这个方法相当于addFirst
     *
     * @throws IllegalStateException {@inheritDoc}
     * @throws ClassCastException {@inheritDoc}
     * @throws NullPointerException if the specified element is null
     * @throws IllegalArgumentException {@inheritDoc}
     */
    void push(E e);
}
