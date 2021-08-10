package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import sun.misc.Unsafe;

/**
 * {@link "https://mp.weixin.qq.com/s/HrLtyZq0czr51Ijqe6gYvw"}
 * {@link "https://blog.csdn.net/weixin_30342639/article/details/107372644"}
 *
 * @author Doug Lea
 * @since 1.5
 */
public abstract class AbstractQueuedSynchronizer extends AbstractOwnableSynchronizer implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    //创建一个初始同步状态为零的新AbstractQueuedSynchronizer实例
    protected AbstractQueuedSynchronizer() {
    }

    /**
     * 等待队列节点类，包括节点对应的线程、节点的等待状态等信息。
     *
     * AQS通过内置的FIFO同步队列来完成资源获取线程的排队工作。
     * 如果当前线程获取锁失败时，AQS会将当前线程以及等待状态等信息构造成一个节点（Node）并将其加入同步队列，同时会park当前线程；
     * 当同步状态释放时，则会把节点中的线程唤醒，使其再次尝试获取同步状态。
     *
     * 同步队列由双向链表实现，AQS持有头尾指针（head/tail属性）来管理同步队列
     */
    static final class Node {

        static final Node SHARED = new Node();  // 标识节点当前在共享模式下
        static final Node EXCLUSIVE = null;     // 标识节点当前在独占模式下

        static final int CANCELLED = 1;  // 标记节点是取消状态，CLH队列中等待超时或者被中断的线程，需要从CLH队列中去掉
        static final int SIGNAL = -1;    // 当前节点的后继节点对应的线程需要被唤醒(表示后继节点的状态)
        static final int CONDITION = -2; // 当前节点进入等待队列中
        // 用在共享模式中,表示节点是可以唤醒传播的。CLH队列此时不需要等待前一个节点释放锁之后,该节点再获取锁
        // 共享模式下所有处于该状态的节点都可以获取到锁,而这个传播唤醒的动作就是通过标记为PROPAGATE状态来实现
        static final int PROPAGATE = -3;

        volatile int waitStatus; // 记录当前节点的状态，除了上述四种状态外，还有一个初始状态0

        volatile Node prev;      // CLH队列中用来表示前一个节点
        volatile Node next;      // CLH队列中用来表示后一个节点
        volatile Thread thread;  // 用来记录当前被阻塞的线程

        Node nextWaiter;  // 条件队列中用来表示下一个节点

        //如果节点在共享模式下等待，则返回true
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        //返回上一个节点，如果为null则抛出NullPointerException
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        Node() {    // 用于建立初始head节点或共享标记
        }

        Node(Thread thread, Node mode) {     // 用于addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        Node(Thread thread, int waitStatus) { // 用于Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * 等待队列的头节点，延迟初始化。除了初始化之外，它只能通过sethead方法进行修改。
     * 注意:如果head存在，它的waitStatus保证不会被取消
     */
    // head指针永远会指向一个空节点。如果当前节点被剔除掉，而后面的节点变成第一个节点的时候，
    // 此时就会清空该节点里面的内容（waitStatus不会被清除），将head指针指向它。这样做的目的是为了方便进行判断
    private transient volatile Node head;

    /**
     * 等待队列的尾节点，延迟初始化。仅通过方法enq修改，以添加新的等待节点.
     */
    private transient volatile Node tail;
    private volatile int state;  // 当前锁的状态：0代表没有被占用，大于0代表锁已被线程占用(锁可以重入，每次重入都+1)
    private transient Thread exclusiveOwnerThread; // 继承自AbstractOwnableSynchronizer 用来记录当前独占锁的线程是谁

    //返回同步状态的当前值。该操作具有volatile读的内存语义。
    protected final int getState() {
        return state;
    }

    //设置同步状态的值。该操作具有volatile写的内存语义。
    protected final void setState(int newState) {
        state = newState;
    }

    //使用CAS设置当前状态，保证状态设置的原子性
    protected final boolean compareAndSetState(int expect, int update) {
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    //这个主要就是为了让那些超时时间非常短的线程，不进入超时等待，直接无条件的做快速自旋即可。
    static final long spinForTimeoutThreshold = 1000L;  //自旋超时阈值

    /**
     * 1.通过自旋的方式将node入队，只有node入队成功才返回，否则一直循环。
     * 2.如果队列为空，初始化head/tail，初始化之后再次循环到else分支，将node入队。
     * 3.node入队时，通过CAS将node置为tail。CAS操作失败，说明被其它线程抢先入队了，自旋，直到成功。
     * 4.返回入队node的前驱节点
     */
    private Node enq(final Node node) {
        // 自旋：循环入列，直到成功
        for (; ; ) {
            // 队列尾节点
            Node t = tail;
            // 如果尾结点为null,说明队列未初始化,需初始化队列.
            if (t == null) {
                // 初始化head/tail,初始化之后再次循环到else分支，将node入队
                // CAS将将入节点设置为头节点
                if (compareAndSetHead(new Node()))
                    tail = head;                   // 将尾节点也指向头节点
                // 否则,队列已经被初始化,队尾入队。
            } else {
                // 将加入节点(node)的前趋节点指向尾结点
                node.prev = t;
                // 通过CAS将node置为tail。操作失败，说明被其它线程抢先入队了，自旋，直到成功。
                // 将将入节点设置为尾节点
                if (compareAndSetTail(t, node)) {
                    t.next = node;  // 将新加入节点的前趋节点的后继节点指向新将入的节点
                    return t;       // 返回上一个尾节点(也就是新加入节点的前驱节点)
                }
            }
        }
    }

    /**
     * 将当前线程和给定模式创建节点 并加入等待队列
     *
     * 线程抢锁失败后，封装成node加入队列:
     * 1.队列有tail，可直接入队。入队时，通过CAS将node置为tail。CAS操作失败，说明被其它线程抢先入队了，node需要通过enq()方法入队。
     * 2.队列没有tail，说明队列是空的，node通过enq()方法入队，enq()会初始化head和tail。
     *
     * addWaiter方法就是让nc入队-并且维护队列的链表关系，但是由于情况复杂做了不同处理
     * 主要针对队列是否有初始化，没有初始化则new一个新的Node nn作为对首，nn里面的线程为null
     */
    private Node addWaiter(Node mode) {
        // 线程获取锁失败后，封装成node加入等待队列
        Node node = new Node(Thread.currentThread(), mode);
        // 队列尾结点
        Node pred = tail;
        // 如果有尾结点，从队尾加入队列
        if (pred != null) {
            // 将加入节点(node)的前趋节点指向尾结点
            node.prev = pred;
            // 通过CAS将node置为尾节点。CAS操作失败，说明被其它线程抢先入队了，node需要通过enq()方法入队
            if (compareAndSetTail(pred, node)) {
                pred.next = node; // 将node前趋节点的后继节点指向node
                return node;
            }
        }
        // 如果没有tail，node通过enq()方法入队。
        enq(node);
        return node;
    }

    // 将node节点置为新的head节点,同时将其中的thread和prev属性置空
    // 注意: 这里并不会清空waitStatus值
    private void setHead(Node node) {
        head = node;
        node.thread = null;
        node.prev = null;
    }

    // 唤醒下一个可以被唤醒的节点
    private void unparkSuccessor(Node node) {
        int ws = node.waitStatus;
        // 如果当前节点状态是SIGNAL或者PROPAGATE,将其CAS设置为初始状态0
        // 因为后续会唤醒第一个被阻塞的节点,所以这里节点的状态如果还是SIGNAL就不正确了，
        // 因为SIGNAL表示的是下一个节点是阻塞状态
        if (ws < 0)
            compareAndSetWaitStatus(node, ws, 0);

        Node s = node.next; // head的下一个节点

        // 如果下一个节点为null,或者状态为CANCELLED
        if (s == null || s.waitStatus > 0) {
            s = null;
            // 从CLH队列的尾节点向前遍历到该节点为止,找到该节点往后第一个处于正常阻塞状态的节点
            // 至于为什么这里是从后往前遍历呢？原因就在于插入节点时是尾插法.保证了每个节点的prev
            // 指针不为null,但是prev.next就不一定了.
            for (Node t = tail; t != null && t != node; t = t.prev)
                if (t.waitStatus <= 0)
                    s = t;
        }
        if (s != null)
            LockSupport.unpark(s.thread);  // 唤醒s节点的线程去抢锁
    }

    // 唤醒之后获取写锁的线程
    private void doReleaseShared() {
        for (; ; ) {
            Node h = head;
            // 说明此时CLH队列中至少有两个节点（包括空节点），即至少含有一个真正在等待着的节点
            if (h != null && h != tail) {
                int ws = h.waitStatus;
                // 因为下面要唤醒下一个节点，所以将头节点的状态SIGNAL改为0（因为SIGNAL表示的是下一个节点是阻塞状态）
                // 如果CAS没成功，就继续尝试
                if (ws == Node.SIGNAL) {
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                        continue;
                    // 唤醒下一个可以被唤醒的节点
                    unparkSuccessor(h);
                } else if (ws == 0 &&
                        !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                    continue;
            }
            if (h == head)
                break;
        }
    }

    /**
     * setHeadAndPropagate()方法就是在一个线程获取读锁之后，唤醒它之后排队获取读锁的线程的。
     * 该方法可以保证线程1获取读锁后，唤醒线程2获取读锁，线程2获取读锁后，唤醒线程3获取读锁，直到遇到后继节点是要获取写锁时才结束
     */
    private void setHeadAndPropagate(Node node,  //node所代表的线程一定是当前执行的线程
                                     int propagate) { //propagate则代表tryAcquireShared的返回值(propagate必定为>=0)
        Node h = head;
        setHead(node); // 因为node获取到锁了，所以设置node为head
        if (propagate > 0  //如果propagate > 0成立的话，说明还有剩余共享锁可以获取，那么短路后面条件
                || h == null
                || h.waitStatus < 0
                || (h = head) == null
                || h.waitStatus < 0) {

            Node s = node.next;
            if (s == null || s.isShared()) // node后继节点线程要获取读锁，此时node就是head
                doReleaseShared(); // 唤醒head后继节点（也就是node.next）获取锁
        }
    }

    // 取消当前线程获取锁资源的请求，并完成一些其他的收尾工作 (https://www.jianshu.com/p/01f2046aab64?open_source)
    private void cancelAcquire(Node node) {
        // 非空校验
        if (node == null)
            return;
        // 节点里面的线程清空
        node.thread = null;

        // 从该节点往前寻找一个不是CANCELLED状态的节点（也就是处于正常阻塞状态的节点），
        // 相当于在退出前再做次清理工作。遍历过程中如果遇到了CANCELLED节点，会被剔除出CLH队列 等待GC
        // 这里的实现逻辑是和shouldParkAfterFailedAcquire方法中是类似的，但是有一点
        // 不同的是：这里并没有pred.next = node，而是延迟到了后面的CAS操作中
        Node pred = node.prev;
        while (pred.waitStatus > 0) {
            node.prev = pred = pred.prev;
        }

        // 如果上面遍历时有CANCELLED节点，predNext就指向pred节点的下一个CANCELLED节点
        // 如果上面遍历时没有CANCELLED节点，predNext就指向自己
        Node predNext = pred.next;
        // 将状态改为CANCELLED，也就是在取消获取锁资源。这里不用CAS来改状态是可以的，
        // 因为改的是CANCELLED状态，其他节点遇到CANCELLED节点是会跳过的
        node.waitStatus = Node.CANCELLED;

        //如果当前节点是尾节点,需要重新将当前节点的有效前驱节点设置成尾节点
        if (node == tail && compareAndSetTail(node, pred)) {
            // 尾节点设置成功,则将尾节点的next节点设置为null
            compareAndSetNext(pred, predNext, null);
            // 走到这里说明当前节点不是尾节点
        } else {
            int ws;
            // 如果node既不是tail，又不是head的后继节点
            // 则将node的前继节点的waitStatus置为SIGNAL
            // 并使node的前继节点指向node的后继节点
            if (pred != head &&
                    ((ws = pred.waitStatus) == Node.SIGNAL ||
                            (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                    pred.thread != null) {
                Node next = node.next;
                if (next != null && next.waitStatus <= 0)
                    // cancelAcquire()调用了compareAndSetNext()方法将node的前继节点指向node的后继节点;
                    // 当别的线程在调用cancelAcquire()或者shouldParkAfterFailedAcquire()时，
                    // 会根据prev指针跳过被cancel掉的前继节点，同时，会调整其遍历过的prev指针,将next的pred指针指向pred
                    compareAndSetNext(pred, predNext, next);
            } else {
                // 如果node是head的后继节点，则直接唤醒node的后继节点
                unparkSuccessor(node);
            }

            // node.next指向自己，断开该节点，同时要保证next指针一定要有值，
            // 因为后续在条件队列的isOnSyncQueue方法中会判断节点是否在CLH队列中
            // 其中有一条就是以判断node.next是否为null为准则，如果不为null，就说明
            // 该节点还在CLH队列中
            node.next = node;     // help GC
        }
    }

    /**
     * shouldParkAfterFailedAcquire()方法主要的作用是设置节点的状态为-1以及删除那些已经被取消的节点线程
     *
     * 通过前置节点pred的状态waitStatus 来判断是否可以将node节点线程挂起
     * pred.waitStatus==Node.SIGNAL(-1)时，返回true表示可以挂起node线程，否则返回false
     *
     * @param pred node的前驱节点
     * @param node 当前线程节点
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        int ws = pred.waitStatus;
        // 如果node前驱节点p.waitStatus==Node.SIGNAL(-1),直接将当前线程挂起,等待唤醒.
        if (ws == Node.SIGNAL)
            return true;
        // waitStatus>0,表示节点取消了排队
        if (ws > 0) {
            // 这里检测一下，将不需要排队的线程从队列中删除（因为同步队列中保存的是等锁的线程）
            // 为node找一个waitStatus<=0的前置节点pred
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node;
        } else {
            // 将前驱节点的waitStatus设置成Node.SIGNAL(-1) （释放锁时，有责任唤醒其后继节点）
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    // 进入到该方法,说明在等待获取锁的过程中,该线程被打断.
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    // 将当前线程挂起并检测打断标志
    private final boolean parkAndCheckInterrupt() {
        LockSupport.park(this);
        return Thread.interrupted(); // 判断当前线程在等待获取锁的过程中是否被打断(清除打断标记)
    }

    /**
     * 重点方法!!!
     * 1.只有head的后继节点能去抢锁，一旦抢到锁旧head节点从队列中删除，next被置为新head节点。
     * 2.如果node线程没有获取到锁，将node线程挂起。
     * 3.锁释放时head节点的后继节点唤醒，唤醒之后继续for循环抢锁。
     */
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false; // 线程打断标志
            for (; ; ) {
                // 获取node的前驱节点
                final Node p = node.predecessor();
                // node的前驱节点是head时,可以调用tryAcquire()尝试去获取锁,获取锁成功则将node置为head
                // 只有head的后继节点能去抢锁，一旦抢到锁旧head节点从队列中删除，next被置为新head节点
                if (p == head && tryAcquire(arg)) {
                    // 头指针指向当前节点，意味着该节点将变成一个空节点(头节点永远会指向一个空节点)
                    // 因为在上一行的tryAcquire方法已经成功的情况下,就可以释放CLH队列中的该节点了.
                    setHead(node);
                    // 断开前一个节点的next指针,这样它就成为了一个孤立节点,等待被GC.
                    p.next = null;
                    failed = false;
                    return interrupted;  // 返回false
                }
                // node线程没有获取到锁，继续执行下面另一个if的代码
                // 此时有两种情况：1)node不是head的后继节点，没有资格抢锁；2)node是head的后继节点但抢锁没成功
                // 条件一: 当前获得锁失败后判断是否需要挂起
                // 条件二: 将当前线程挂起,并且再被唤醒时检查线程是否被打断.
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt()) {
                    // 进入到此处,说明当前线程被打断
                    // 只是记录一个标志位而已,不会抛出InterruptedException异常,也就是说不会响应中断.
                    interrupted = true;
                }

            }
        } finally {
            if (failed) {
                // 如果tryAcquire方法中state+1溢出了，就会取消当前线程获取锁资源的请求
                cancelAcquire(node);
            }
        }
    }

    //可中断式获取锁
    private void doAcquireInterruptibly(int arg) throws InterruptedException {
        //将节点插入到同步队列中
        final Node node = addWaiter(Node.EXCLUSIVE); //独占模式
        boolean failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    //线程中断抛异常
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 带超时时间获取锁
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout) throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;  //计算超时的时间点
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }
                nanosTimeout = deadline - System.nanoTime(); //计算剩余的超时时间
                if (nanosTimeout <= 0L)
                    return false;  //超时返回false
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold) //超时时间必须大于1ms,否则自旋
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    //获取共享锁
    private void doAcquireShared(int arg) {
        final Node node = addWaiter(Node.SHARED); //共享模式
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (; ; ) {
                final Node p = node.predecessor();
                if (p == head) { //前驱节点是head，node才能去抢锁
                    int r = tryAcquireShared(arg); //尝试获取读锁，获取到锁返回1，获取不到返回-1
                    if (r >= 0) { // r>0表示抢锁成功
                        setHeadAndPropagate(node, r);
                        p.next = null;  // help GC
                        if (interrupted)
                            selfInterrupt();
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared interruptible mode.
     *
     * @param arg the acquire argument
     */
    private void doAcquireSharedInterruptibly(int arg)
            throws InterruptedException {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared timed mode.
     *
     * @param arg          the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    // AQS使用模板方法设计模式
    // 模板方法，需要子类实现获取锁/释放锁的方法

    /**
     * 尝试获取独占锁
     *
     * 需要子类实现的抢锁的方法
     * 目前可以理解为通过CAS修改state的值，成功即为抢到锁，返回true；否则返回false。
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 独占式释放同步状态
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 共享式获取同步状态
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 共享式释放同步状态
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * 独占式获取同步状态，如果获取失败则插入同步队列进行等待
     * 1.当前线程通过tryAcquire()方法抢锁。
     * 2.线程抢到锁，tryAcquire()返回true，结束。
     * 3.线程没有抢到锁，addWaiter()方法将当前线程封装成node加入同步队列，并将node交由acquireQueued()处理。
     */
    public final void acquire(int arg) {
        // 首先尝试获取资源,如果失败了的话就添加一个新的独占节点,插入到CLH队列尾部.
        if (!tryAcquire(arg) && acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            // 在acquireQueued中会对线程的中断状态做判断,如果中断了则返回true,进入selfInterrupt()方法,恢复线程的中断状态;
            // 但注意此处是在获取到锁之后再响应中断,在获取到锁之前不会做出响应;
            // 进入到该方法,说明在等待获取锁的过程中,该线程被打断.
            selfInterrupt();
    }

    /**
     * 可中断式获取锁
     * 与acquire(int arg)相同，但是该方法响应中断
     */
    public final void acquireInterruptibly(int arg) throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (!tryAcquire(arg))
            //线程获取锁失败
            doAcquireInterruptibly(arg);
    }

    /**
     * 在acquireInterruptibly基础上增加了超时等待功能，在超时时间内没有获得同步状态返回false;
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout) throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquire(arg) ||
                doAcquireNanos(arg, nanosTimeout);
    }

    /**
     * 独占式释放同步状态，该方法会在释放同步状态之后，将同步队列中头节点的下一个节点包含的线程唤醒.
     * 释放锁之后，唤醒head的后继节点next，next节点会进入for循环的下一次循环去抢锁。
     */
    public final boolean release(int arg) {
        if (tryRelease(arg)) {  // 子类实现的释放锁的方法
            Node h = head;
            if (h != null && h.waitStatus != 0)
                unparkSuccessor(h);  // 唤醒head的后继节点
            return true;
        }
        return false;
    }

    //获取读锁
    public final void acquireShared(int arg) {
        if (tryAcquireShared(arg) < 0)
            doAcquireShared(arg); //尝试获取读锁，获取到锁返回1，获取不到返回-1。
    }

    /**
     * 在acquireShared方法基础上增加了能响应中断的功能
     */
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (tryAcquireShared(arg) < 0)
            doAcquireSharedInterruptibly(arg);
    }

    /**
     * 在acquireSharedInterruptibly基础上增加了超时等待的功能
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquireShared(arg) >= 0 ||
                doAcquireSharedNanos(arg, nanosTimeout);
    }

    //读锁释放
    public final boolean releaseShared(int arg) {
        if (tryReleaseShared(arg)) {
            /**
             * 到这里，说明已经没有任何线程占用锁，调用doReleaseShared()唤醒之后获取写锁的线程
             * 如果同步队列中还有线程在排队，head后继节点的线程一定是要获取写锁，因为线程持有读锁时会把它之后要获取读锁的线程全部唤醒
             */
            doReleaseShared();
            return true;
        }
        return false;
    }


    //同步队列中是否有线程等待
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    //是否存在锁竞争
    public final boolean hasContended() {
        return head != null;
    }

    //返回队列中第一个(等待时间最长的)线程，如果当前队列中没有线程，则返回null
    public final Thread getFirstQueuedThread() {
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    //获取同步队列中的第一个线程
    private Thread fullGetFirstQueuedThread() {
        Node h, s;
        Thread st;
        if (((h = head) != null && (s = h.next) != null &&
                s.prev == head && (st = s.thread) != null) ||
                ((h = head) != null && (s = h.next) != null &&
                        s.prev == head && (st = s.thread) != null))
            return st;

        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
    }

    //如果给定线程当前在队列中，则返回true。 此实现将遍历队列以确定给定线程的存在。
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.thread == thread)
                return true;
        return false;
    }

    //队列中第一个等锁的线程请求的是不是写锁
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null &&
                (s = h.next) != null &&
                !s.isShared() &&   //head后继节点线程请求写锁
                s.thread != null;
    }

    /**
     * {@link "https://blog.csdn.net/wenzhouxiaomayi77/article/details/104682122"}
     * 判断同步队列中是否有排队等待的线程
     *
     * 整个方法如果最后返回false，说明没有排队的node,则去加锁，如果返回true，说明有排队的node。
     */
    public final boolean hasQueuedPredecessors() {
        Node t = tail;
        Node h = head;
        Node s;
        return h != t &&
                ((s = h.next) == null || s.thread != Thread.currentThread());
    }

    //获取同步队列中线程数量
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null)
                ++n;
        }
        return n;
    }

    //获取同步队列中所有线程的集合
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    public String toString() {
        int s = getState();
        String q = hasQueuedThreads() ? "non" : "";
        return super.toString() +
                "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions

    //判断该节点是否在同步队列中
    final boolean isOnSyncQueue(Node node) {
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;
        if (node.next != null) // 如果有后继节点，则必须在队列上
            return true;
        return findNodeFromTail(node);
    }

    //从尾节点开始找（判断该节点是否在同步队列中）
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (; ; ) {
            if (t == node)
                return true;
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    //将节点从条件队列转移到同步队列。如果成功返回true。
    final boolean transferForSignal(Node node) {
        //1. 更新状态为0
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
            return false;

        //2.将该节点移入到同步队列中去
        Node p = enq(node);
        int ws = p.waitStatus;
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
            LockSupport.unpark(node.thread);
        return true;
    }

    /**
     * Transfers node, if necessary, to sync queue after a cancelled wait.
     * Returns true if thread was cancelled before being signalled.
     *
     * @param node the node
     * @return true if cancelled before the node was signalled
     */
    final boolean transferAfterCancelledWait(Node node) {
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            enq(node);
            return true;
        }
        while (!isOnSyncQueue(node))
            Thread.yield();
        return false;
    }

    //释放所有锁
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            int savedState = getState();
            if (release(savedState)) {
                failed = false;
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed)
                node.waitStatus = Node.CANCELLED;
        }
    }

    //查询给定的条件对象是否使用此同步器作为其锁
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    //查询是否有线程在指定等待队列中
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    //返回指定等待队列中的线程数的估计数
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    //获取指定等待队列中所有线程集合
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }


    //等待队列是一个单向队列
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;

        //条件队列的第一个节点
        private transient Node firstWaiter;
        //条件队列的最后一个节点
        private transient Node lastWaiter;

        public ConditionObject() {
        }

        //增加一个新的waiter到等待队列
        private Node addConditionWaiter() {
            Node t = lastWaiter;

            if (t != null && t.waitStatus != Node.CONDITION) {
                unlinkCancelledWaiters(); //移除取消等待的Waiter
                t = lastWaiter; // t指向调整后的lastWaiter
            }
            Node node = new Node(Thread.currentThread(), Node.CONDITION);  //构造等待节点
            if (t == null)
                firstWaiter = node; //说明是第一个节点
            else
                t.nextWaiter = node;  //从队尾加入
            lastWaiter = node;  //将lastWaiter指向新加入的节点
            return node;
        }

        //唤醒一个等待在condition上的线程，将该线程从等待队列中转移到同步队列中，如果在同步队列中能够竞争到Lock则可以从等待方法中返回。
        private void doSignal(Node first) {
            do {
                if ((firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;
                //1. 将头结点从等待队列中移除
                first.nextWaiter = null;
                //2. while中transferForSignal方法对头结点做真正的处理
            } while (!transferForSignal(first) &&
                    (first = firstWaiter) != null);
        }

        //唤醒所有等待对列的线程
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
            } while (first != null);
        }

        /**
         * 从条件队列中解除已取消的waiter节点的链接。仅在持有锁时调用。
         * 当在条件等待期间取消发生时，以及当看到lastWaiter已被取消时插入一个waiter时，都会调用此函数。
         */
        private void unlinkCancelledWaiters() {
            Node t = firstWaiter;
            Node trail = null;
            while (t != null) {
                Node next = t.nextWaiter;
                if (t.waitStatus != Node.CONDITION) {
                    t.nextWaiter = null;
                    if (trail == null)
                        firstWaiter = next;
                    else
                        trail.nextWaiter = next;
                    if (next == null)
                        lastWaiter = trail;
                } else
                    trail = t;
                t = next;
            }
        }

        /**
         * 唤醒一个等待在condition上的线程，将该线程从等待队列中转移到同步队列中，如果在同步队列中能够竞争到Lock则可以从等待方法中返回。
         *
         * 调用condition的signal的前提条件是当前线程已经获取了lock，
         * 该方法会使得等待队列中的头节点即等待时间最长的那个节点移入到同步队列，
         * 而移入到同步队列后才有机会使得等待线程被唤醒，
         * 即从await方法中的LockSupport.park(this)方法中返回，从而才有机会使得调用await方法的线程成功退出
         */
        public final void signal() {
            //1. 先检测当前线程是否已经获取lock
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            //2. 获取等待队列中第一个节点，之后的操作都是针对这个节点
            Node first = firstWaiter;
            if (first != null)
                doSignal(first);
        }

        //唤醒等待队列中所有线程
        public final void signalAll() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignalAll(first);
        }

        //不可中断等待
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean interrupted = false;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if (Thread.interrupted())
                    interrupted = true;
            }
            if (acquireQueued(node, savedState) || interrupted)
                selfInterrupt();
        }

        //模式意味着在退出等待时重新中断
        private static final int REINTERRUPT = 1;

        //模式意味着在退出等待时抛出InterruptedException
        private static final int THROW_IE = -1;

        private int checkInterruptWhileWaiting(Node node) {
            return Thread.interrupted() ?
                    (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                    0;
        }

        //抛出InterruptedException，重新中断当前线程，或者什么也不做，具体取决于模式
        private void reportInterruptAfterWait(int interruptMode)
                throws InterruptedException {
            if (interruptMode == THROW_IE)
                throw new InterruptedException();
            else if (interruptMode == REINTERRUPT)
                selfInterrupt();
        }

        /**
         * 当当前线程调用condition.await()方法后，会使得当前线程释放lock然后加入到等待队列中，
         * 直至被signal/signalAll后会使得当前线程从等待队列中移至到同步队列中去，
         * 直到获得了lock后才会从await方法返回，或者在等待时被中断会做中断处理。
         */
        public final void await() throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            // 1. 将当前线程包装成Node，尾插入到等待队列中
            Node node = addConditionWaiter();
            // 2. 释放当前线程所占用的lock，在释放的过程中会唤醒同步队列中的下一个节点
            int savedState = fullyRelease(node);
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {//是否在同步队列中
                // 3. 当前线程进入到等待状态
                LockSupport.park(this);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            // 4. 自旋等待获取到同步状态（即获取到lock）
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null) // clean up if cancelled
                unlinkCancelledWaiters();
            // 5. 处理被中断的情况
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
        }

        //超时时间等待（纳秒）
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return deadline - System.nanoTime();
        }

        //等待至某个时间
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        //超时时间等待
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }


        //如果该条件是由给定的同步对象创建的，则返回true
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        //查询是否有线程在等待队列中
        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }

        //返回等待队列的线程数的估计数
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }

        //获取等待队列中所有线程集合
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }


    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset(AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset(AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset(AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset(Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset(Node.class.getDeclaredField("next"));
        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

    //CAS 给头节点赋值
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    //CAS 尾头节点赋值
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    //CAS 修改node的WaitStatus属性值
    private static final boolean compareAndSetWaitStatus(Node node, int expect, int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset, expect, update);
    }

    //CAS 修改node的后继节点
    private static final boolean compareAndSetNext(Node node, Node expect, Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}
