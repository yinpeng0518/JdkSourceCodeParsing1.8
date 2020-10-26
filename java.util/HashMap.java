package java.util;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import jdk.internal.access.SharedSecrets;

/**
 * {@link "https://blog.csdn.net/lylzdd/article/details/86536424"}
 * {@link "https://www.gclearning.cn/views/JDK%E6%BA%90%E7%A0%81%E9%82%A3%E4%BA%9B%E4%BA%8B%E5%84%BF/2019/030519.html"}
 *
 * 2357
 *
 * @author Doug Lea
 * @author Josh Bloch
 * @author Arthur van Hoff
 * @author Neal Gafter
 * @see Object#hashCode()
 * @see Collection
 * @see Map
 * @see TreeMap
 * @see Hashtable
 * @since 1.2
 */
public class HashMap<K, V> extends AbstractMap<K, V> implements Map<K, V>, Cloneable, Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 362498820763181265L;

    //Node数组的默认长度，16，这个值必须是2的次方
    static final int DEFAULT_INITIAL_CAPACITY = 1 << 4;

    //Node数组的最大长度，最大扩容长度
    static final int MAXIMUM_CAPACITY = 1 << 30;

    /**
     * 默认负载因子:
     * 负载因子是哈希表在自动扩容之前能承受容量的一种尺度。
     * 当哈希表的数目超出了负载因子与当前容量的乘积时，则要对该哈希表进行rehash操作（扩容操作）。
     */
    static final float DEFAULT_LOAD_FACTOR = 0.75f;

    /**
     * 链表转换为树的阈值，超过这个长度的链表会被转换为红黑树，
     * 当然，不止这一个条件，在下面的源码部分会看到。
     */
    static final int TREEIFY_THRESHOLD = 8;

    //当进行resize操作时，小于这个长度的树会被转换为链表
    static final int UNTREEIFY_THRESHOLD = 6;

    /**
     * 当Map里面的数量超过这个值时，表中的桶才能进行树形化 ，否则桶内元素太多时会扩容，
     * 而不是树形化 为了避免进行扩容、树形化选择的冲突，这个值不能小于 4 * TREEIFY_THRESHOLD
     */
    static final int MIN_TREEIFY_CAPACITY = 64;

    //没有树化的时候基础节点，也就是数组里面存的节点，在一个数组元素内组成链表的节点。
    static class Node<K, V> implements Map.Entry<K, V> {
        final int hash;   //哈希值，HashMap根据该值确定记录的位置
        final K key;
        V value;
        Node<K, V> next;  //链表下一个节点

        Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }

        public final K getKey() {
            return key;
        }

        public final V getValue() {
            return value;
        }

        public final String toString() {
            return key + "=" + value;
        }

        public final int hashCode() {
            return Objects.hashCode(key) ^ Objects.hashCode(value);
        }

        public final V setValue(V newValue) {
            V oldValue = value;
            value = newValue;
            return oldValue;
        }

        /**
         * equals（）
         * 作用：判断2个Entry是否相等，必须key和value都相等，才返回true
         */
        public final boolean equals(Object o) {
            if (o == this)
                return true;
            if (o instanceof Map.Entry) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                if (Objects.equals(key, e.getKey()) &&
                        Objects.equals(value, e.getValue()))
                    return true;
            }
            return false;
        }
    }

    /* ---------------- Static utilities -------------- */

    /**
     * 如果key为null,则hash为0; 否则先获得key的hashcode，然后与这个值的高16位相异或。(异或：两个相同为0，相异为1)
     *
     * 这叫做扰动函数，这样做是为了减少hash碰撞的概率，将对象尽可能的分布到整个数组table中。同时，因为这个操作是一个高频操作，所以采用位运算来进行。
     * 那么为什么这样做可以实现这个效果呢？
     * 因为在hashmap中数组分配位置的时候采用的是(n - 1) & hash这个操作。n - 1相当于是一段“11…1”(因为数组长度n一定是2的幂)，
     * 和hash进行与操作将会保留所有位数低于n - 1的二进制长度的值，所以这个操作就相当于取余数，一定能将hash分配到[0, n - 1]的范围之间。
     * 但是很多时候低位的hashCode可能相似度很高，如果不考虑高位的话，就算原本的key的hash算法分布的再怎么松散，只考虑低几位的话，分配到同一个位置的概率也会很高。
     * 这个时候，如果将hashCode的高位特征也加入进去，则可以大大降低hash碰撞的概率。
     */
    static final int hash(Object key) {
        int h;
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }

    /**
     * 如果对象x的类型实现了Comparable<C>接口，那么返回x的类型，否则返回nul
     * 总之方法的目的就是为了看看x的class是否 implements  Comparable<>
     */
    static Class<?> comparableClassFor(Object x) {
        if (x instanceof Comparable) {
            Class<?> c;
            Type[] ts, as;
            ParameterizedType p;
            if ((c = x.getClass()) == String.class) // String类型直接返回
                return c;
            if ((ts = c.getGenericInterfaces()) != null) { //类所实现的所有接口的一个数组
                for (Type t : ts) {
                    // 如果当前接口t是个泛型接口
                    // 如果该泛型接口t的原始类型p 是 Comparable 接口
                    // 如果该Comparable接口p只定义了一个泛型参数
                    // 如果这一个泛型参数的类型就是c，那么返回c
                    if ((t instanceof ParameterizedType) &&
                            ((p = (ParameterizedType) t).getRawType() ==
                                    Comparable.class) &&
                            (as = p.getActualTypeArguments()) != null &&
                            as.length == 1 && as[0] == c)
                        return c;
                }
            }
        }
        return null;
    }

    //如果x的类型是kc，返回k.compareTo(x)的比较结果;如果x为空，或者类型不是kc，返回0
    @SuppressWarnings({"rawtypes", "unchecked"})
    static int compareComparables(Class<?> kc, Object k, Object x) {
        return (x == null || x.getClass() != kc ? 0 :
                ((Comparable) k).compareTo(x));
    }

    /**
     * 返回大于输入参数且最近的2的整数次幂的数。比如10，则返回16。
     *
     * 那么这是如何做到的呢？我们以cap = 6为例来计算：
     *
     * 1. cap - 1 = 5 转化为二进制
     * 0000 0000 0000 0000 0000 0000 0000 0101
     * 它有29个Leading Zero。
     *
     * 2. -1转化为二进制
     * 111 1111 1111 1111 1111 1111 1111 1111
     *
     * 3. 这个数右移位29，也就是
     * 0000 0000 0000 0000 0000 0000 0000 0111
     *
     * 4. 加一，也就是
     * 0000 0000 0000 0000 0000 0000 0000 1000
     * 即为8。
     */
    static final int tableSizeFor(int cap) {
        int n = -1 >>> Integer.numberOfLeadingZeros(cap - 1);
        return (n < 0) ? 1 : (n >= MAXIMUN_CAPACITY) ? MAXIMUN_CAPACITY : n + 1;
    }

    /* ---------------- Fields -------------- */

    //hashmap中的数组，长度必须为2的幂,惰性初始化(第一次使用的时候进行初始化，put操作才会初始化对象,调用构造函数时不会初始化)
    transient Node<K, V>[] table;

    //entrySet保存key和value 用于迭代
    transient Set<Map.Entry<K, V>> entrySet;

    //存放元素的个数，但不等于数组的长度
    transient int size;

    /**
     * 计数器，fail-fast机制相关
     * 你可以当成一个在高并发读写操作时的判断，举个例子：
     * 一个线程A迭代遍历a,modCount=expectedModCount值为1，执行过程中，一个线程B修改了a,modCount=2，线程A在遍历时判断了modCount<>expectedModCount,抛错
     * 当然，这个只是简单的检查，并不能得到保证
     */
    transient int modCount;

    //阈值，当实际大小超过阈值（容量*负载因子）的时候，会进行扩容
    int threshold;

    //哈希表的加载因子
    final float loadFactor;

    /* ---------------- Public operations -------------- */

    //构造一个空的 HashMap具有指定的初始容量和负载因子
    public HashMap(int initialCapacity, float loadFactor) {
        //判断初始化容量initialCapacity是否小于0，小于0 则抛异常
        if (initialCapacity < 0)
            throw new IllegalArgumentException("Illegal initial capacity: " + initialCapacity);
        //如果初始容量大于最大容量限制，则初始容量等于最大容量
        if (initialCapacity > MAXIMUM_CAPACITY)
            initialCapacity = MAXIMUM_CAPACITY;
        //如果loadFactor小于0 或者是非数字 抛出异常
        if (loadFactor <= 0 || Float.isNaN(loadFactor))
            throw new IllegalArgumentException("Illegal load factor: " + loadFactor);
        this.loadFactor = loadFactor;
        //根据初始容量设置阈值
        this.threshold = tableSizeFor(initialCapacity);
    }

    //构造一个空的 HashMap具有指定的初始容量和默认负载因子（0.75）
    public HashMap(int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    //构造一个空的 HashMap，默认初始容量（16）和默认负载因子（0.75）
    public HashMap() {
        this.loadFactor = DEFAULT_LOAD_FACTOR;
    }

    //使用与指定映射相同的映射构造一个新的HashMap。HashMap使用默认负载因子(0.75)和足以在指定映射中保存映射的初始容量创建
    public HashMap(Map<? extends K, ? extends V> m) {
        this.loadFactor = DEFAULT_LOAD_FACTOR;
        putMapEntries(m, false);
    }

    /**
     * putMapEntries函数会被HashMap的拷贝构造函数public HashMap(Map<? extends K, ? extends V> m)或者Map接口的putAll函数（被HashMap给实现了）调用到。
     * 该函数由于是默认的包访问权限，所以一般情况下用户无法调用。
     */
    final void putMapEntries(Map<? extends K, ? extends V> m, boolean evict) {
        int s = m.size();
        //传入map的大小不为0，
        if (s > 0) {
            //判断table是否已经被初始化
            if (table == null) {
                //未被初始化，判断m中元素的个数放入当前map中是否会超出最大容量的阈值
                float ft = ((float) s / loadFactor) + 1.0F;
                int t = ((ft < (float) MAXIMUM_CAPACITY) ? (int) ft : MAXIMUM_CAPACITY);
                //计算得到的t大于阈值 重新设置阈值
                if (t > threshold)
                    threshold = tableSizeFor(t);
            } else {
                while (s > threshold && table.length < MAXIMUM_CAPACITY)
                    //当前map已经初始化，并且添加的元素长度大于阈值，需要进行扩容操作
                    resize();
            }
            //循环里的putVal可能也会触发resize
            for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
                K key = e.getKey();
                V value = e.getValue();
                putVal(hash(key), key, value, false, evict);
            }
        }
    }

    //返回hashmap中键值对的数量
    public int size() {
        return size;
    }

    //如果hashmap不包含键值对，则返回true
    public boolean isEmpty() {
        return size == 0;
    }

    //get()方法会调用getNode()方法。所以核心在于getNode()方法
    public V get(Object key) {
        Node<K, V> e;
        return (e = getNode(hash(key), key)) == null ? null : e.value;
    }

    /**
     * getNode()方法的流程如下:
     *
     * 1.如果table为空或者使用(n - 1) & hash方法找到的对应下标出为空，返回null
     * 2.不为空，分为三种情况
     * 2.1 第一个节点就是要找的key，直接返回对应的节点
     * 2.2 当前节点是红黑树类型，在红黑树中找对应的节点
     * 2.3 当前是一个链表，在链表中找对应节点。
     *
     * 多线程环境下，get()方法有什么问题？
     * 由于table数组没有保证内存可见性，在一个线程删除/添加某个结点后并不能及时刷新到主内存中，另一个线程通过 get()方法获取该结点就会出错。
     */
    final Node<K, V> getNode(int hash, Object key) {
        Node<K, V>[] tab;
        Node<K, V> first, e;
        int n;
        K k;
        if ((tab = table) != null && (n = tab.length) > 0 && (first = tab[(n - 1) & hash]) != null) { // 取出数组中hash值对应的Node
            // 先检查第一个Node是不是要找的Node，如果是就返回
            if (first.hash == hash && ((k = first.key) == key || (key != null && key.equals(k))))
                return first;
            // 第一个Node不是要找的Node，到后面的链表/红黑树中找
            if ((e = first.next) != null) {
                if (first instanceof TreeNode) //当前节点是红黑树类型，在红黑树中找对应的节点
                    return ((TreeNode<K, V>) first).getTreeNode(hash, key);
                // 遍历后面的链表，找到key值和hash值都相同的Node返回
                do {
                    if (e.hash == hash && ((k = e.key) == key || (key != null && key.equals(k))))
                        return e;
                } while ((e = e.next) != null);
            }
        }
        return null;
    }

    //如果此map中包含指定键的映射，则返回true
    public boolean containsKey(Object key) {
        return getNode(hash(key), key) != null;
    }

    public V put(K key, V value) {
        return putVal(hash(key), key, value, false, true);
    }

    /**
     * 数组下标没有对应 hash 值，直接 newNode()添加
     * 数组下标有对应 hash 值，添加到链表最后
     * 链表超过最大长度(8)，将链表改为红黑树再添加元素
     *
     * 多线程环境下，put()方法会有是什么问题呢？
     *
     * 1. tab[i] = newNode(hash, key, value, null),首当其冲的就是这种赋值操作，很容易丢数据。
     * 如因为没有锁，线程A,线程 B 可以同时检查得到tab[1]==null，然后线程A 设置了tab[1]=Node(A)，马上线程B 又设置tab[1]=Node(B)，
     * 那么线程A 设置的数据就丢失了，而正确的操作应该是将 Node(B)插入链表中Node(A).next=Node(B)。
     * 2. ++size，普通变量 size 没有可见性保证，++操作也没有保证原子性，这个计算在多线程环境下一定是有问题的.
     * 3. 在一个线程 put()过程中，可能有其他线程有 put 和 remove，导致当前线程 put 失败。
     */
    final V putVal(int hash, K key, V value, boolean onlyIfAbsent, //是否覆盖原有值，true,不覆盖原有值
                   boolean evict) {  //LinkedHashMap实现afterNodeInsertion方法时调用，这里相当于占位符的作用
        Node<K, V>[] tab; //Node数组
        Node<K, V> p;
        int n,  //数组容量
        int i;
        //如果数组为null或者容量为0，就进行resize()扩容
        if ((tab = table) == null || (n = tab.length) == 0)
            //这里也说明了第一次初始化是在这里，而不是使用构造方法，排除putMapEntries方式
            n = (tab = resize()).length;
        //通过i = (n - 1) & hash计算数组下标
        if ((p = tab[i = (n - 1) & hash]) == null)
            //如果当前数组中当前位置为null，直接插入新节点
            tab[i] = newNode(hash, key, value, null);
            // table数组中hash值对应位置有数据
        else {
            Node<K, V> e;
            K k;
            //如果数组中当前位置元素hash等于传入的hash并且key的值也相等(使用key对象的equals()方法判断)
            if (p.hash == hash && ((k = p.key) == key || (key != null && key.equals(k))))
                e = p;
                // hash值对应位置结点为TreeNode，调用红黑树的插值方法
            else if (p instanceof TreeNode) e = ((TreeNode<K, V>) p).putTreeVal(this, tab, hash, key, value);

                // hash值对应位置结点为Node，将数据插入链表
            else {
                for (int binCount = 0; ; ++binCount) {
                    if ((e = p.next) == null) {
                        p.next = newNode(hash, key, value, null);
                        // 如果链表结点数达到8个，将链表转换为红黑树
                        // 如果是往链表中插入新节点，那么调用treeifyBin()方法并不一定会将链表转化为红黑树，在这个方法中还有一步判断条件，
                        // 也就是前面提到的MIN_TREEIFY_CAPACITY。如果数组长度小于这个值则会执行resize()
                        if (binCount >= TREEIFY_THRESHOLD - 1)
                            treeifyBin(tab, hash);
                        break;
                    }
                    //如果链表中存在相同的key,跳出循环，使用新值替换原本的旧值
                    if (e.hash == hash && ((k = e.key) == key || (key != null && key.equals(k))))
                        break;
                    p = e;
                }
            }
            //使用新值替换原本的旧值
            if (e != null) {
                V oldValue = e.value;
                if (!onlyIfAbsent || oldValue == null)
                    e.value = value;
                afterNodeAccess(e);
                return oldValue;  //返回旧值
            }
        }

        //只有加入了新的键值对的时候才会进行modCount++，修改原来的键值对则不会。
        ++modCount;

        // 如果size超过了阈值，扩容
        if (++size > threshold)
            resize();
        afterNodeInsertion(evict);
        return null;
    }

    /**
     * 扩容的过程:
     * 1. 如果table == null,则为HashMap的初始化,生成空table返回即可;
     * 2. 如果table不为空,需要重新计算table的长度, newLength = oldLength << 1(注,如果原oldLength已经到了上限,则newLength = oldLength);
     * 3. 遍历oldTable:
     * 3.1 首节点为空, 本次循环结束;
     * 3.2 无后续节点, 重新计算hash位, 本次循环结束;
     * 3.3 当前是红黑树, 走红黑树的重定位;
     * 3.4 当前是链表, JAVA7时还需要重新计算hash位, 但是JAVA8做了优化, 通过(e.hash & oldCap) == 0来判断是否需要移位; 如果为真则在原位不动, 否则则需要移动到当前hash槽位 + oldCap的位置;
     */
    final Node<K, V>[] resize() {
        Node<K, V>[] oldTab = table;                        //保存扩容前的Node数组
        int oldCap = (oldTab == null) ? 0 : oldTab.length;  //扩容前的Node数组的容量大小
        int oldThr = threshold;                             //扩容前的阈值
        int newCap = 0;                                     //扩容后的数组容量（长度）
        int newThr = 0;                                     //扩容后的阈值

        //扩容前的数组不为空
        if (oldCap > 0) {
            //扩容前的Node数组容量大于等于设置的最大容量，不会进行扩容，阈值设置为Integer.MAX_VALUE
            if (oldCap >= MAXIMUM_CAPACITY) {
                threshold = Integer.MAX_VALUE;
                return oldTab;
                // 如果扩容前的数组容量扩大为2倍依然没有超过最大容量，
                // 并且扩容前的Node数组容量大于等于数组的默认容量，
                // 扩容后的数组容量值为扩容前的map的容量的2倍，并且扩容后的阈值同样设置为扩容前的两倍,
                // 反之，则只设置扩容后的容量值为扩容前的map的容量的2倍
                // 这里newCap已经在条件里赋值了
            } else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY
                    && oldCap >= DEFAULT_INITIAL_CAPACITY)
                newThr = oldThr << 1;
            // 扩容前的数组未初始化并且使用了有参构造函数构造
            // 这里在oldCap = 0时执行，这里oldThr > 0说明初始化时是有参初始化构造的map
            // 自己可以试下无参构造函数，threshold的值为0
        } else if (oldThr > 0)
            newCap = oldThr;
            //扩容前的数组未初始化并且使用了无参构造函数构造,对应使用new HashMap()初始化后，第一次put的时候
        else {
            newCap = DEFAULT_INITIAL_CAPACITY; //扩容后的容量 = 默认容量
            newThr = (int) (DEFAULT_LOAD_FACTOR * DEFAULT_INITIAL_CAPACITY); //扩容后的阈值 = 默认容量 * 负载因子
        }

        /**
         * 上边设置了新容量和新的阈值，执行到这里，你应该发现只有newThr可能没被赋值，所以这里要继续进行一个操作，来对newThr进行赋值
         * 两种情况：
         * 1.扩容前的node数组容量有值且扩容后容量超过最大值或者原node数组容量小于默认初始容量16
         * 2.使用有参构造函数，第一次put操作时上边代码里没有设置newThr
         */
        if (newThr == 0) {
            //应该得到的新阈值ft = 新容量 * 负载因子
            float ft = (float) newCap * loadFactor;
            //假如新容量小于最大容量并且ft小于最大容量则新的阈值设置为ft,否则设置成int最大值
            newThr = (newCap < MAXIMUM_CAPACITY && ft < (float) MAXIMUM_CAPACITY ?
                    (int) ft : Integer.MAX_VALUE);
        }
        //执行到这，扩容后的容量和阈值都计算完毕,阈值设置为新阈值
        threshold = newThr;
        //创建扩容后的Node数组
        @SuppressWarnings({"rawtypes", "unchecked"})
        Node<K, V>[] newTab = (Node<K, V>[]) new Node[newCap];
        //如果是初始化数组，到这里就结束了，返回newTab即可
        table = newTab; //切换为扩容后的Node数组，此时还未进行将旧数组拷贝到新数组
        if (oldTab != null) {
            //原有数组不为空，将原有数组数据拷贝到新数组中
            for (int j = 0; j < oldCap; ++j) {
                Node<K, V> e;
                //非空元素才进行赋值
                if ((e = oldTab[j]) != null) {
                    oldTab[j] = null;  //原有数值引用置空，方便GC
                    //如果该数组位置上只有单个元素，直接迁移这个元素
                    if (e.next == null)
                        newTab[e.hash & (newCap - 1)] = e;
                        // e是一个红黑树节点类型(e instanced TreeNode)，调用split方法将树拆分成两颗，如果树太小的就转化回链表
                    else if (e instanceof TreeNode)
                        ((TreeNode<K, V>) e).split(this, newTab, j, oldCap);

                        // 这块是处理链表的情况，
                        // 需要将此链表拆成两个链表，放到新的数组中，并且保留原来的先后顺序
                        // loHead、loTail 对应一条链表，hiHead、hiTail 对应另一条链表，代码还是比较简单的
                    else {
                        Node<K, V> loHead = null, loTail = null;
                        Node<K, V> hiHead = null, hiTail = null;
                        Node<K, V> next;
                        do {
                            next = e.next;
                            //ashMap扩容时的rehash方法中(e.hash & oldCap) == 0算法推导
                            @link "https://blog.csdn.net/u010425839/article/details/106620440/"
                            if ((e.hash & oldCap) == 0) {
                                if (loTail == null)
                                    loHead = e;
                                else
                                    loTail.next = e;
                                loTail = e;
                            } else {
                                if (hiTail == null)
                                    hiHead = e;
                                else
                                    hiTail.next = e;
                                hiTail = e;
                            }
                        } while ((e = next) != null);

                        if (loTail != null) {
                            loTail.next = null;
                            // 第一条链表(e.hash & oldCap) == 0
                            newTab[j] = loHead;
                        }

                        if (hiTail != null) {
                            hiTail.next = null;
                            // 第二条链表的新的位置是 j + oldCap
                            newTab[j + oldCap] = hiHead;
                        }
                    }
                }
            }
        }
        return newTab;
    }

    /**
     * Replaces all linked nodes in bin at index for given hash unless
     * table is too small, in which case resizes instead.
     */
    final void treeifyBin(Node<K, V>[] tab, int hash) {
        int n, index;
        Node<K, V> e;
        if (tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY)
            resize();
        else if ((e = tab[index = (n - 1) & hash]) != null) {
            TreeNode<K, V> hd = null, tl = null;
            do {
                TreeNode<K, V> p = replacementTreeNode(e, null);
                if (tl == null)
                    hd = p;
                else {
                    p.prev = tl;
                    tl.next = p;
                }
                tl = p;
            } while ((e = e.next) != null);
            if ((tab[index] = hd) != null)
                hd.treeify(tab);
        }
    }

    public void putAll(Map<? extends K, ? extends V> m) {
        putMapEntries(m, true);
    }

    /**
     * Removes the mapping for the specified key from this map if present.
     *
     * @param key key whose mapping is to be removed from the map
     * @return the previous value associated with {@code key}, or
     * {@code null} if there was no mapping for {@code key}.
     * (A {@code null} return can also indicate that the map
     * previously associated {@code null} with {@code key}.)
     */
    public V remove(Object key) {
        Node<K, V> e;
        return (e = removeNode(hash(key), key, null, false, true)) == null ?
                null : e.value;
    }

    /**
     * Implements Map.remove and related methods.
     *
     * @param hash       hash for key
     * @param key        the key
     * @param value      the value to match if matchValue, else ignored
     * @param matchValue if true only remove if value is equal
     * @param movable    if false do not move other nodes while removing
     * @return the node, or null if none
     */
    final Node<K, V> removeNode(int hash, Object key, Object value,
                                boolean matchValue, boolean movable) {
        Node<K, V>[] tab;
        Node<K, V> p;
        int n, index;
        if ((tab = table) != null && (n = tab.length) > 0 &&
                (p = tab[index = (n - 1) & hash]) != null) {
            Node<K, V> node = null, e;
            K k;
            V v;
            if (p.hash == hash &&
                    ((k = p.key) == key || (key != null && key.equals(k))))
                node = p;
            else if ((e = p.next) != null) {
                if (p instanceof TreeNode)
                    node = ((TreeNode<K, V>) p).getTreeNode(hash, key);
                else {
                    do {
                        if (e.hash == hash &&
                                ((k = e.key) == key ||
                                        (key != null && key.equals(k)))) {
                            node = e;
                            break;
                        }
                        p = e;
                    } while ((e = e.next) != null);
                }
            }
            if (node != null && (!matchValue || (v = node.value) == value ||
                    (value != null && value.equals(v)))) {
                if (node instanceof TreeNode)
                    ((TreeNode<K, V>) node).removeTreeNode(this, tab, movable);
                else if (node == p)
                    tab[index] = node.next;
                else
                    p.next = node.next;
                ++modCount;
                --size;
                afterNodeRemoval(node);
                return node;
            }
        }
        return null;
    }

    /**
     * Removes all of the mappings from this map.
     * The map will be empty after this call returns.
     */
    public void clear() {
        Node<K, V>[] tab;
        modCount++;
        if ((tab = table) != null && size > 0) {
            size = 0;
            for (int i = 0; i < tab.length; ++i)
                tab[i] = null;
        }
    }

    /**
     * Returns {@code true} if this map maps one or more keys to the
     * specified value.
     *
     * @param value value whose presence in this map is to be tested
     * @return {@code true} if this map maps one or more keys to the
     * specified value
     */
    public boolean containsValue(Object value) {
        Node<K, V>[] tab;
        V v;
        if ((tab = table) != null && size > 0) {
            for (Node<K, V> e : tab) {
                for (; e != null; e = e.next) {
                    if ((v = e.value) == value ||
                            (value != null && value.equals(v)))
                        return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns a {@link Set} view of the keys contained in this map.
     * The set is backed by the map, so changes to the map are
     * reflected in the set, and vice-versa.  If the map is modified
     * while an iteration over the set is in progress (except through
     * the iterator's own {@code remove} operation), the results of
     * the iteration are undefined.  The set supports element removal,
     * which removes the corresponding mapping from the map, via the
     * {@code Iterator.remove}, {@code Set.remove},
     * {@code removeAll}, {@code retainAll}, and {@code clear}
     * operations.  It does not support the {@code add} or {@code addAll}
     * operations.
     *
     * @return a set view of the keys contained in this map
     */
    public Set<K> keySet() {
        Set<K> ks = keySet;
        if (ks == null) {
            ks = new KeySet();
            keySet = ks;
        }
        return ks;
    }

    /**
     * Prepares the array for {@link Collection#toArray(Object[])} implementation.
     * If supplied array is smaller than this map size, a new array is allocated.
     * If supplied array is bigger than this map size, a null is written at size index.
     *
     * @param a   an original array passed to {@code toArray()} method
     * @param <T> type of array elements
     * @return an array ready to be filled and returned from {@code toArray()} method.
     */
    @SuppressWarnings("unchecked")
    final <T> T[] prepareArray(T[] a) {
        int size = this.size;
        if (a.length < size) {
            return (T[]) java.lang.reflect.Array
                    .newInstance(a.getClass().getComponentType(), size);
        }
        if (a.length > size) {
            a[size] = null;
        }
        return a;
    }

    /**
     * Fills an array with this map keys and returns it. This method assumes
     * that input array is big enough to fit all the keys. Use
     * {@link #prepareArray(Object[])} to ensure this.
     *
     * @param a   an array to fill
     * @param <T> type of array elements
     * @return supplied array
     */
    <T> T[] keysToArray(T[] a) {
        Object[] r = a;
        Node<K, V>[] tab;
        int idx = 0;
        if (size > 0 && (tab = table) != null) {
            for (Node<K, V> e : tab) {
                for (; e != null; e = e.next) {
                    r[idx++] = e.key;
                }
            }
        }
        return a;
    }

    /**
     * Fills an array with this map values and returns it. This method assumes
     * that input array is big enough to fit all the values. Use
     * {@link #prepareArray(Object[])} to ensure this.
     *
     * @param a   an array to fill
     * @param <T> type of array elements
     * @return supplied array
     */
    <T> T[] valuesToArray(T[] a) {
        Object[] r = a;
        Node<K, V>[] tab;
        int idx = 0;
        if (size > 0 && (tab = table) != null) {
            for (Node<K, V> e : tab) {
                for (; e != null; e = e.next) {
                    r[idx++] = e.value;
                }
            }
        }
        return a;
    }

    final class KeySet extends AbstractSet<K> {
        public final int size() {
            return size;
        }

        public final void clear() {
            HashMap.this.clear();
        }

        public final Iterator<K> iterator() {
            return new KeyIterator();
        }

        public final boolean contains(Object o) {
            return containsKey(o);
        }

        public final boolean remove(Object key) {
            return removeNode(hash(key), key, null, false, true) != null;
        }

        public final Spliterator<K> spliterator() {
            return new KeySpliterator<>(HashMap.this, 0, -1, 0, 0);
        }

        public Object[] toArray() {
            return keysToArray(new Object[size]);
        }

        public <T> T[] toArray(T[] a) {
            return keysToArray(prepareArray(a));
        }

        public final void forEach(Consumer<? super K> action) {
            Node<K, V>[] tab;
            if (action == null)
                throw new NullPointerException();
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (Node<K, V> e : tab) {
                    for (; e != null; e = e.next)
                        action.accept(e.key);
                }
                if (modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }
    }

    /**
     * Returns a {@link Collection} view of the values contained in this map.
     * The collection is backed by the map, so changes to the map are
     * reflected in the collection, and vice-versa.  If the map is
     * modified while an iteration over the collection is in progress
     * (except through the iterator's own {@code remove} operation),
     * the results of the iteration are undefined.  The collection
     * supports element removal, which removes the corresponding
     * mapping from the map, via the {@code Iterator.remove},
     * {@code Collection.remove}, {@code removeAll},
     * {@code retainAll} and {@code clear} operations.  It does not
     * support the {@code add} or {@code addAll} operations.
     *
     * @return a view of the values contained in this map
     */
    public Collection<V> values() {
        Collection<V> vs = values;
        if (vs == null) {
            vs = new Values();
            values = vs;
        }
        return vs;
    }

    final class Values extends AbstractCollection<V> {
        public final int size() {
            return size;
        }

        public final void clear() {
            HashMap.this.clear();
        }

        public final Iterator<V> iterator() {
            return new ValueIterator();
        }

        public final boolean contains(Object o) {
            return containsValue(o);
        }

        public final Spliterator<V> spliterator() {
            return new ValueSpliterator<>(HashMap.this, 0, -1, 0, 0);
        }

        public Object[] toArray() {
            return valuesToArray(new Object[size]);
        }

        public <T> T[] toArray(T[] a) {
            return valuesToArray(prepareArray(a));
        }

        public final void forEach(Consumer<? super V> action) {
            Node<K, V>[] tab;
            if (action == null)
                throw new NullPointerException();
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (Node<K, V> e : tab) {
                    for (; e != null; e = e.next)
                        action.accept(e.value);
                }
                if (modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }
    }

    /**
     * Returns a {@link Set} view of the mappings contained in this map.
     * The set is backed by the map, so changes to the map are
     * reflected in the set, and vice-versa.  If the map is modified
     * while an iteration over the set is in progress (except through
     * the iterator's own {@code remove} operation, or through the
     * {@code setValue} operation on a map entry returned by the
     * iterator) the results of the iteration are undefined.  The set
     * supports element removal, which removes the corresponding
     * mapping from the map, via the {@code Iterator.remove},
     * {@code Set.remove}, {@code removeAll}, {@code retainAll} and
     * {@code clear} operations.  It does not support the
     * {@code add} or {@code addAll} operations.
     *
     * @return a set view of the mappings contained in this map
     */
    public Set<Map.Entry<K, V>> entrySet() {
        Set<Map.Entry<K, V>> es;
        return (es = entrySet) == null ? (entrySet = new EntrySet()) : es;
    }

    final class EntrySet extends AbstractSet<Map.Entry<K, V>> {
        public final int size() {
            return size;
        }

        public final void clear() {
            HashMap.this.clear();
        }

        public final Iterator<Map.Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        public final boolean contains(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            Object key = e.getKey();
            Node<K, V> candidate = getNode(hash(key), key);
            return candidate != null && candidate.equals(e);
        }

        public final boolean remove(Object o) {
            if (o instanceof Map.Entry) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                Object key = e.getKey();
                Object value = e.getValue();
                return removeNode(hash(key), key, value, true, true) != null;
            }
            return false;
        }

        public final Spliterator<Map.Entry<K, V>> spliterator() {
            return new EntrySpliterator<>(HashMap.this, 0, -1, 0, 0);
        }

        public final void forEach(Consumer<? super Map.Entry<K, V>> action) {
            Node<K, V>[] tab;
            if (action == null)
                throw new NullPointerException();
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (Node<K, V> e : tab) {
                    for (; e != null; e = e.next)
                        action.accept(e);
                }
                if (modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }
    }

    // Overrides of JDK8 Map extension methods

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        Node<K, V> e;
        return (e = getNode(hash(key), key)) == null ? defaultValue : e.value;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return putVal(hash(key), key, value, true, true);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return removeNode(hash(key), key, value, true, true) != null;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        Node<K, V> e;
        V v;
        if ((e = getNode(hash(key), key)) != null &&
                ((v = e.value) == oldValue || (v != null && v.equals(oldValue)))) {
            e.value = newValue;
            afterNodeAccess(e);
            return true;
        }
        return false;
    }

    @Override
    public V replace(K key, V value) {
        Node<K, V> e;
        if ((e = getNode(hash(key), key)) != null) {
            V oldValue = e.value;
            e.value = value;
            afterNodeAccess(e);
            return oldValue;
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method will, on a best-effort basis, throw a
     * {@link ConcurrentModificationException} if it is detected that the
     * mapping function modifies this map during computation.
     *
     * @throws ConcurrentModificationException if it is detected that the
     *                                         mapping function modified this map
     */
    @Override
    public V computeIfAbsent(K key,
                             Function<? super K, ? extends V> mappingFunction) {
        if (mappingFunction == null)
            throw new NullPointerException();
        int hash = hash(key);
        Node<K, V>[] tab;
        Node<K, V> first;
        int n, i;
        int binCount = 0;
        TreeNode<K, V> t = null;
        Node<K, V> old = null;
        if (size > threshold || (tab = table) == null ||
                (n = tab.length) == 0)
            n = (tab = resize()).length;
        if ((first = tab[i = (n - 1) & hash]) != null) {
            if (first instanceof TreeNode)
                old = (t = (TreeNode<K, V>) first).getTreeNode(hash, key);
            else {
                Node<K, V> e = first;
                K k;
                do {
                    if (e.hash == hash &&
                            ((k = e.key) == key || (key != null && key.equals(k)))) {
                        old = e;
                        break;
                    }
                    ++binCount;
                } while ((e = e.next) != null);
            }
            V oldValue;
            if (old != null && (oldValue = old.value) != null) {
                afterNodeAccess(old);
                return oldValue;
            }
        }
        int mc = modCount;
        V v = mappingFunction.apply(key);
        if (mc != modCount) {
            throw new ConcurrentModificationException();
        }
        if (v == null) {
            return null;
        } else if (old != null) {
            old.value = v;
            afterNodeAccess(old);
            return v;
        } else if (t != null)
            t.putTreeVal(this, tab, hash, key, v);
        else {
            tab[i] = newNode(hash, key, v, first);
            if (binCount >= TREEIFY_THRESHOLD - 1)
                treeifyBin(tab, hash);
        }
        modCount = mc + 1;
        ++size;
        afterNodeInsertion(true);
        return v;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method will, on a best-effort basis, throw a
     * {@link ConcurrentModificationException} if it is detected that the
     * remapping function modifies this map during computation.
     *
     * @throws ConcurrentModificationException if it is detected that the
     *                                         remapping function modified this map
     */
    @Override
    public V computeIfPresent(K key,
                              BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (remappingFunction == null)
            throw new NullPointerException();
        Node<K, V> e;
        V oldValue;
        int hash = hash(key);
        if ((e = getNode(hash, key)) != null &&
                (oldValue = e.value) != null) {
            int mc = modCount;
            V v = remappingFunction.apply(key, oldValue);
            if (mc != modCount) {
                throw new ConcurrentModificationException();
            }
            if (v != null) {
                e.value = v;
                afterNodeAccess(e);
                return v;
            } else
                removeNode(hash, key, null, false, true);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method will, on a best-effort basis, throw a
     * {@link ConcurrentModificationException} if it is detected that the
     * remapping function modifies this map during computation.
     *
     * @throws ConcurrentModificationException if it is detected that the
     *                                         remapping function modified this map
     */
    @Override
    public V compute(K key,
                     BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (remappingFunction == null)
            throw new NullPointerException();
        int hash = hash(key);
        Node<K, V>[] tab;
        Node<K, V> first;
        int n, i;
        int binCount = 0;
        TreeNode<K, V> t = null;
        Node<K, V> old = null;
        if (size > threshold || (tab = table) == null ||
                (n = tab.length) == 0)
            n = (tab = resize()).length;
        if ((first = tab[i = (n - 1) & hash]) != null) {
            if (first instanceof TreeNode)
                old = (t = (TreeNode<K, V>) first).getTreeNode(hash, key);
            else {
                Node<K, V> e = first;
                K k;
                do {
                    if (e.hash == hash &&
                            ((k = e.key) == key || (key != null && key.equals(k)))) {
                        old = e;
                        break;
                    }
                    ++binCount;
                } while ((e = e.next) != null);
            }
        }
        V oldValue = (old == null) ? null : old.value;
        int mc = modCount;
        V v = remappingFunction.apply(key, oldValue);
        if (mc != modCount) {
            throw new ConcurrentModificationException();
        }
        if (old != null) {
            if (v != null) {
                old.value = v;
                afterNodeAccess(old);
            } else
                removeNode(hash, key, null, false, true);
        } else if (v != null) {
            if (t != null)
                t.putTreeVal(this, tab, hash, key, v);
            else {
                tab[i] = newNode(hash, key, v, first);
                if (binCount >= TREEIFY_THRESHOLD - 1)
                    treeifyBin(tab, hash);
            }
            modCount = mc + 1;
            ++size;
            afterNodeInsertion(true);
        }
        return v;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method will, on a best-effort basis, throw a
     * {@link ConcurrentModificationException} if it is detected that the
     * remapping function modifies this map during computation.
     *
     * @throws ConcurrentModificationException if it is detected that the
     *                                         remapping function modified this map
     */
    @Override
    public V merge(K key, V value,
                   BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (value == null || remappingFunction == null)
            throw new NullPointerException();
        int hash = hash(key);
        Node<K, V>[] tab;
        Node<K, V> first;
        int n, i;
        int binCount = 0;
        TreeNode<K, V> t = null;
        Node<K, V> old = null;
        if (size > threshold || (tab = table) == null ||
                (n = tab.length) == 0)
            n = (tab = resize()).length;
        if ((first = tab[i = (n - 1) & hash]) != null) {
            if (first instanceof TreeNode)
                old = (t = (TreeNode<K, V>) first).getTreeNode(hash, key);
            else {
                Node<K, V> e = first;
                K k;
                do {
                    if (e.hash == hash &&
                            ((k = e.key) == key || (key != null && key.equals(k)))) {
                        old = e;
                        break;
                    }
                    ++binCount;
                } while ((e = e.next) != null);
            }
        }
        if (old != null) {
            V v;
            if (old.value != null) {
                int mc = modCount;
                v = remappingFunction.apply(old.value, value);
                if (mc != modCount) {
                    throw new ConcurrentModificationException();
                }
            } else {
                v = value;
            }
            if (v != null) {
                old.value = v;
                afterNodeAccess(old);
            } else
                removeNode(hash, key, null, false, true);
            return v;
        } else {
            if (t != null)
                t.putTreeVal(this, tab, hash, key, value);
            else {
                tab[i] = newNode(hash, key, value, first);
                if (binCount >= TREEIFY_THRESHOLD - 1)
                    treeifyBin(tab, hash);
            }
            ++modCount;
            ++size;
            afterNodeInsertion(true);
            return value;
        }
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Node<K, V>[] tab;
        if (action == null)
            throw new NullPointerException();
        if (size > 0 && (tab = table) != null) {
            int mc = modCount;
            for (Node<K, V> e : tab) {
                for (; e != null; e = e.next)
                    action.accept(e.key, e.value);
            }
            if (modCount != mc)
                throw new ConcurrentModificationException();
        }
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Node<K, V>[] tab;
        if (function == null)
            throw new NullPointerException();
        if (size > 0 && (tab = table) != null) {
            int mc = modCount;
            for (Node<K, V> e : tab) {
                for (; e != null; e = e.next) {
                    e.value = function.apply(e.key, e.value);
                }
            }
            if (modCount != mc)
                throw new ConcurrentModificationException();
        }
    }

    /* ------------------------------------------------------------ */
    // Cloning and serialization

    //返回这个HashMap实例的浅拷贝:键和值本身不会被克隆。
    @SuppressWarnings("unchecked")
    @Override
    public Object clone() {
        HashMap<K, V> result;
        try {
            result = (HashMap<K, V>) super.clone();
        } catch (CloneNotSupportedException e) {
            // this shouldn't happen, since we are Cloneable
            throw new InternalError(e);
        }
        result.reinitialize();
        result.putMapEntries(this, false);
        return result;
    }

    //加载因子
    final float loadFactor() {
        return loadFactor;
    }

    //map的容量
    final int capacity() {
        return (table != null) ? table.length :
                (threshold > 0) ? threshold :
                        DEFAULT_INITIAL_CAPACITY;
    }

    //将此Map保存到流(即序列化它)
    @java.io.Serial
    private void writeObject(java.io.ObjectOutputStream s) throws IOException {
        int buckets = capacity();  //map的容量
        s.defaultWriteObject();
        s.writeInt(buckets);
        s.writeInt(size);
        internalWriteEntries(s);
    }

    //从流重新构造此Map(即对其进行反序列化)
    @java.io.Serial
    private void readObject(java.io.ObjectInputStream s) throws IOException, ClassNotFoundException {
        // Read in the threshold (ignored), loadfactor, and any hidden stuff
        s.defaultReadObject();
        reinitialize();
        if (loadFactor <= 0 || Float.isNaN(loadFactor))
            throw new InvalidObjectException("Illegal load factor: " +
                    loadFactor);
        s.readInt();                // Read and ignore number of buckets
        int mappings = s.readInt(); // Read number of mappings (size)
        if (mappings < 0)
            throw new InvalidObjectException("Illegal mappings count: " + mappings);
        else if (mappings > 0) { // (if zero, use defaults)
            // Size the table using given load factor only if within
            // range of 0.25...4.0
            float lf = Math.min(Math.max(0.25f, loadFactor), 4.0f);
            float fc = (float) mappings / lf + 1.0f;
            int cap = ((fc < DEFAULT_INITIAL_CAPACITY) ?
                    DEFAULT_INITIAL_CAPACITY :
                    (fc >= MAXIMUM_CAPACITY) ?
                            MAXIMUM_CAPACITY :
                            tableSizeFor((int) fc));
            float ft = (float) cap * lf;
            threshold = ((cap < MAXIMUM_CAPACITY && ft < MAXIMUM_CAPACITY) ?
                    (int) ft : Integer.MAX_VALUE);

            // Check Map.Entry[].class since it's the nearest public type to
            // what we're actually creating.
            SharedSecrets.getJavaObjectInputStreamAccess().checkArray(s, Map.Entry[].class, cap);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Node<K, V>[] tab = (Node<K, V>[]) new Node[cap];
            table = tab;

            // Read the keys and values, and put the mappings in the HashMap
            for (int i = 0; i < mappings; i++) {
                @SuppressWarnings("unchecked")
                K key = (K) s.readObject();
                @SuppressWarnings("unchecked")
                V value = (V) s.readObject();
                putVal(hash(key), key, value, false, false);
            }
        }
    }

    /* ------------------------------------------------------------ */
    // iterators

    abstract class HashIterator {
        Node<K, V> next;        // next entry to return
        Node<K, V> current;     // current entry
        int expectedModCount;  // for fast-fail
        int index;             // current slot

        HashIterator() {
            expectedModCount = modCount;
            Node<K, V>[] t = table;
            current = next = null;
            index = 0;
            if (t != null && size > 0) { // advance to first entry
                do {
                } while (index < t.length && (next = t[index++]) == null);
            }
        }

        public final boolean hasNext() {
            return next != null;
        }

        final Node<K, V> nextNode() {
            Node<K, V>[] t;
            Node<K, V> e = next;
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
            if (e == null)
                throw new NoSuchElementException();
            if ((next = (current = e).next) == null && (t = table) != null) {
                do {
                } while (index < t.length && (next = t[index++]) == null);
            }
            return e;
        }

        public final void remove() {
            Node<K, V> p = current;
            if (p == null)
                throw new IllegalStateException();
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
            current = null;
            removeNode(p.hash, p.key, null, false, false);
            expectedModCount = modCount;
        }
    }

    final class KeyIterator extends HashIterator
            implements Iterator<K> {
        public final K next() {
            return nextNode().key;
        }
    }

    final class ValueIterator extends HashIterator
            implements Iterator<V> {
        public final V next() {
            return nextNode().value;
        }
    }

    final class EntryIterator extends HashIterator
            implements Iterator<Map.Entry<K, V>> {
        public final Map.Entry<K, V> next() {
            return nextNode();
        }
    }

    /* ------------------------------------------------------------ */
    // spliterators

    static class HashMapSpliterator<K, V> {
        final HashMap<K, V> map;
        Node<K, V> current;          // current node
        int index;                  // current index, modified on advance/split
        int fence;                  // one past last index
        int est;                    // size estimate
        int expectedModCount;       // for comodification checks

        HashMapSpliterator(HashMap<K, V> m, int origin,
                           int fence, int est,
                           int expectedModCount) {
            this.map = m;
            this.index = origin;
            this.fence = fence;
            this.est = est;
            this.expectedModCount = expectedModCount;
        }

        final int getFence() { // initialize fence and size on first use
            int hi;
            if ((hi = fence) < 0) {
                HashMap<K, V> m = map;
                est = m.size;
                expectedModCount = m.modCount;
                Node<K, V>[] tab = m.table;
                hi = fence = (tab == null) ? 0 : tab.length;
            }
            return hi;
        }

        public final long estimateSize() {
            getFence(); // force init
            return (long) est;
        }
    }

    static final class KeySpliterator<K, V>
            extends HashMapSpliterator<K, V>
            implements Spliterator<K> {
        KeySpliterator(HashMap<K, V> m, int origin, int fence, int est,
                       int expectedModCount) {
            super(m, origin, fence, est, expectedModCount);
        }

        public KeySpliterator<K, V> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid || current != null) ? null :
                    new KeySpliterator<>(map, lo, index = mid, est >>>= 1,
                            expectedModCount);
        }

        public void forEachRemaining(Consumer<? super K> action) {
            int i, hi, mc;
            if (action == null)
                throw new NullPointerException();
            HashMap<K, V> m = map;
            Node<K, V>[] tab = m.table;
            if ((hi = fence) < 0) {
                mc = expectedModCount = m.modCount;
                hi = fence = (tab == null) ? 0 : tab.length;
            } else
                mc = expectedModCount;
            if (tab != null && tab.length >= hi &&
                    (i = index) >= 0 && (i < (index = hi) || current != null)) {
                Node<K, V> p = current;
                current = null;
                do {
                    if (p == null)
                        p = tab[i++];
                    else {
                        action.accept(p.key);
                        p = p.next;
                    }
                } while (p != null || i < hi);
                if (m.modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }

        public boolean tryAdvance(Consumer<? super K> action) {
            int hi;
            if (action == null)
                throw new NullPointerException();
            Node<K, V>[] tab = map.table;
            if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
                while (current != null || index < hi) {
                    if (current == null)
                        current = tab[index++];
                    else {
                        K k = current.key;
                        current = current.next;
                        action.accept(k);
                        if (map.modCount != expectedModCount)
                            throw new ConcurrentModificationException();
                        return true;
                    }
                }
            }
            return false;
        }

        public int characteristics() {
            return (fence < 0 || est == map.size ? Spliterator.SIZED : 0) |
                    Spliterator.DISTINCT;
        }
    }

    static final class ValueSpliterator<K, V>
            extends HashMapSpliterator<K, V>
            implements Spliterator<V> {
        ValueSpliterator(HashMap<K, V> m, int origin, int fence, int est,
                         int expectedModCount) {
            super(m, origin, fence, est, expectedModCount);
        }

        public ValueSpliterator<K, V> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid || current != null) ? null :
                    new ValueSpliterator<>(map, lo, index = mid, est >>>= 1,
                            expectedModCount);
        }

        public void forEachRemaining(Consumer<? super V> action) {
            int i, hi, mc;
            if (action == null)
                throw new NullPointerException();
            HashMap<K, V> m = map;
            Node<K, V>[] tab = m.table;
            if ((hi = fence) < 0) {
                mc = expectedModCount = m.modCount;
                hi = fence = (tab == null) ? 0 : tab.length;
            } else
                mc = expectedModCount;
            if (tab != null && tab.length >= hi &&
                    (i = index) >= 0 && (i < (index = hi) || current != null)) {
                Node<K, V> p = current;
                current = null;
                do {
                    if (p == null)
                        p = tab[i++];
                    else {
                        action.accept(p.value);
                        p = p.next;
                    }
                } while (p != null || i < hi);
                if (m.modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }

        public boolean tryAdvance(Consumer<? super V> action) {
            int hi;
            if (action == null)
                throw new NullPointerException();
            Node<K, V>[] tab = map.table;
            if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
                while (current != null || index < hi) {
                    if (current == null)
                        current = tab[index++];
                    else {
                        V v = current.value;
                        current = current.next;
                        action.accept(v);
                        if (map.modCount != expectedModCount)
                            throw new ConcurrentModificationException();
                        return true;
                    }
                }
            }
            return false;
        }

        public int characteristics() {
            return (fence < 0 || est == map.size ? Spliterator.SIZED : 0);
        }
    }

    static final class EntrySpliterator<K, V>
            extends HashMapSpliterator<K, V>
            implements Spliterator<Map.Entry<K, V>> {
        EntrySpliterator(HashMap<K, V> m, int origin, int fence, int est,
                         int expectedModCount) {
            super(m, origin, fence, est, expectedModCount);
        }

        public EntrySpliterator<K, V> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid || current != null) ? null :
                    new EntrySpliterator<>(map, lo, index = mid, est >>>= 1,
                            expectedModCount);
        }

        public void forEachRemaining(Consumer<? super Map.Entry<K, V>> action) {
            int i, hi, mc;
            if (action == null)
                throw new NullPointerException();
            HashMap<K, V> m = map;
            Node<K, V>[] tab = m.table;
            if ((hi = fence) < 0) {
                mc = expectedModCount = m.modCount;
                hi = fence = (tab == null) ? 0 : tab.length;
            } else
                mc = expectedModCount;
            if (tab != null && tab.length >= hi &&
                    (i = index) >= 0 && (i < (index = hi) || current != null)) {
                Node<K, V> p = current;
                current = null;
                do {
                    if (p == null)
                        p = tab[i++];
                    else {
                        action.accept(p);
                        p = p.next;
                    }
                } while (p != null || i < hi);
                if (m.modCount != mc)
                    throw new ConcurrentModificationException();
            }
        }

        public boolean tryAdvance(Consumer<? super Map.Entry<K, V>> action) {
            int hi;
            if (action == null)
                throw new NullPointerException();
            Node<K, V>[] tab = map.table;
            if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
                while (current != null || index < hi) {
                    if (current == null)
                        current = tab[index++];
                    else {
                        Node<K, V> e = current;
                        current = current.next;
                        action.accept(e);
                        if (map.modCount != expectedModCount)
                            throw new ConcurrentModificationException();
                        return true;
                    }
                }
            }
            return false;
        }

        public int characteristics() {
            return (fence < 0 || est == map.size ? Spliterator.SIZED : 0) |
                    Spliterator.DISTINCT;
        }
    }

    /* ------------------------------------------------------------ */
    // LinkedHashMap support


    /*
     * The following package-protected methods are designed to be
     * overridden by LinkedHashMap, but not by any other subclass.
     * Nearly all other internal methods are also package-protected
     * but are declared final, so can be used by LinkedHashMap, view
     * classes, and HashSet.
     */

    // Create a regular (non-tree) node
    Node<K, V> newNode(int hash, K key, V value, Node<K, V> next) {
        return new Node<>(hash, key, value, next);
    }

    // For conversion from TreeNodes to plain nodes
    Node<K, V> replacementNode(Node<K, V> p, Node<K, V> next) {
        return new Node<>(p.hash, p.key, p.value, next);
    }

    // Create a tree bin node
    TreeNode<K, V> newTreeNode(int hash, K key, V value, Node<K, V> next) {
        return new TreeNode<>(hash, key, value, next);
    }

    // For treeifyBin
    TreeNode<K, V> replacementTreeNode(Node<K, V> p, Node<K, V> next) {
        return new TreeNode<>(p.hash, p.key, p.value, next);
    }

    //重置到初始默认状态。由克隆和readObject调用
    void reinitialize() {
        table = null;
        entrySet = null;
        keySet = null;
        values = null;
        modCount = 0;
        threshold = 0;
        size = 0;
    }

    // Callbacks to allow LinkedHashMap post-actions
    void afterNodeAccess(Node<K, V> p) {
    }

    void afterNodeInsertion(boolean evict) {
    }

    void afterNodeRemoval(Node<K, V> p) {
    }

    //仅从writeObject调用，以确保兼容顺序
    void internalWriteEntries(java.io.ObjectOutputStream s) throws IOException {
        Node<K, V>[] tab;
        if (size > 0 && (tab = table) != null) {
            for (Node<K, V> e : tab) {
                for (; e != null; e = e.next) {
                    s.writeObject(e.key);
                    s.writeObject(e.value);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    // Tree bins

    /**
     * {@link "https://www.gclearning.cn/views/JDK%E6%BA%90%E7%A0%81%E9%82%A3%E4%BA%9B%E4%BA%8B%E5%84%BF/2019/040511.html"}
     * {@link "https://zh.wikipedia.org/wiki/红黑树"}
     * {@link "https://en.wikipedia.org/wiki/Red–black_tree"}
     *
     * 红黑树介绍:
     * 1.节点是红色或黑色
     * 2.根节点是黑色
     * 3.每个叶子节点都是黑色的空节点(NIL节点）
     * 4 父子不能同为红色
     * 5.从任一节点到其每个叶子的所有路径都包含相同数目的黑色节点
     *
     * TreeNode具备红黑树的性质，但又有异于红黑树，由于两种存储形式的存在，插入或删除都要实现两部分的逻辑以及进行当前存储形式的判断
     * 链栈: prev + next实现，节点数<7
     * 树: parent + left + right实现
     */
    static final class TreeNode<K, V> extends LinkedHashMap.Entry<K, V> {
        TreeNode<K, V> parent;  //父节点
        TreeNode<K, V> left;    //左子节点
        TreeNode<K, V> right;   //右子节点
        TreeNode<K, V> prev;    //前驱节点
        boolean red;            //是否是红色节点

        //构造方法直接调用的父类方法,最终还是HashMap.Node的构造方法
        TreeNode(int hash, K key, V val, Node<K, V> next) {
            super(hash, key, val, next);
        }

        //返回根节点TreeNode,实现上非常简单，不断向上遍历，找到父节点为空的节点即为根节点
        final TreeNode<K, V> root() {
            for (TreeNode<K, V> r = this, p; ; ) {
                if ((p = r.parent) == null)
                    return r;
                r = p;
            }
        }

        /**
         * 确保根节点是bin桶（数组tab的其中一个元素）中的第一个节点,如果不是，则进行操作，将根节点放到tab数组上，
         * 这个跟HashMap结构有关，数组（链表+红黑树），在进行树化，结构调整时，根节点可能会变化，
         * 原有数组tab对应索引指向的树节点需要进行改变，指向新的根节点,这里注意下，移动时不是修改红黑树是修改的链表结构，prev和next属性
         */
        static <K, V> void moveRootToFront(Node<K, V>[] tab, TreeNode<K, V> root) {
            int n; //数组的长度
            if (root != null && tab != null && (n = tab.length) > 0) {
                int index = (n - 1) & root.hash;  //找到当前树所在的bin桶位置（即数组tab的位置）
                TreeNode<K, V> first = (TreeNode<K, V>) tab[index];  //将tab[index]的树节点记录下来为first
                if (root != first) {  //root没有落在tab数组上，修改为root在tab数组上
                    Node<K, V> rn; //rn为root的后继节点
                    tab[index] = root; //这里即替换掉tab[index]指向的原有节点，可以理解成现在指向root节点
                    TreeNode<K, V> rp = root.prev;  //rp为root的前驱节点
                    if ((rn = root.next) != null)
                        ((TreeNode<K, V>) rn).prev = rp;  //将root前后节点关联
                    if (rp != null)
                        rp.next = rn;
                    //first和root节点进行关联，first的前一个节点为root
                    if (first != null)
                        first.prev = root;
                    // 修改root的链表属性
                    root.next = first;
                    root.prev = null;
                }
                //检查红黑树一致性
                assert checkInvariants(root);
            }
        }

        //使用给定的hash和key从根节点开始查找。kc参数在第一次使用比较键时缓存comparableClassFor(key)。
        final TreeNode<K, V> find(int h, Object k, Class<?> kc) {
            TreeNode<K, V> p = this;  //当前节点（根节点）
            do {
                int ph,
                int dir;
                K pk;
                TreeNode<K, V> pl = p.left,  //左节点
                TreeNode<K, V> pr = p.right, //右节点
                TreeNode<K, V> q;
                //查找节点hash值小于p的hash值，搜索左子树
                if ((ph = p.hash) > h)
                    p = pl;
                    //查找节点hash值大于p的hash值，搜索右子树
                else if (ph < h)
                    p = pr;
                    //key值相同，说明就是此p节点
                else if ((pk = p.key) == k || (k != null && k.equals(pk)))
                    return p;
                    //若hash相等但key不等，向左右子树非空的一侧移动
                else if (pl == null)
                    p = pr;
                else if (pr == null)
                    p = pl;
                    //左右两边都不为空
                    //判断kc是否是k实现的可比较的类，是就比较k和pk的值，k<pk向左子树移动否则向右子树移动
                    //hash相等，key不等，使用key实现的compareTo判断大小
                else if ((kc != null ||
                        (kc = comparableClassFor(k)) != null) &&
                        (dir = compareComparables(kc, k, pk)) != 0)
                    p = (dir < 0) ? pl : pr;
                    //上面所有情况依旧不能判断左右，就先递归判断右子树，看是否匹配上，匹配上就赋右子树，否则左子树
                else if ((q = pr.find(h, k, kc)) != null)
                    return q;
                else
                    p = pl;
            } while (p != null);
            return null;
        }

        /**
         * Calls find for root node.
         */
        final TreeNode<K, V> getTreeNode(int h, Object k) {
            return ((parent != null) ? root() : this).find(h, k, null);
        }

        /**
         * Tie-breaking utility for ordering insertions when equal
         * hashCodes and non-comparable. We don't require a total
         * order, just a consistent insertion rule to maintain
         * equivalence across rebalancings. Tie-breaking further than
         * necessary simplifies testing a bit.
         */
        static int tieBreakOrder(Object a, Object b) {
            int d;
            if (a == null || b == null ||
                    (d = a.getClass().getName().
                            compareTo(b.getClass().getName())) == 0)
                d = (System.identityHashCode(a) <= System.identityHashCode(b) ?
                        -1 : 1);
            return d;
        }

        /**
         * Forms tree of the nodes linked from this node.
         */
        final void treeify(Node<K, V>[] tab) {
            TreeNode<K, V> root = null;
            for (TreeNode<K, V> x = this, next; x != null; x = next) {
                next = (TreeNode<K, V>) x.next;
                x.left = x.right = null;
                if (root == null) {
                    x.parent = null;
                    x.red = false;
                    root = x;
                } else {
                    K k = x.key;
                    int h = x.hash;
                    Class<?> kc = null;
                    for (TreeNode<K, V> p = root; ; ) {
                        int dir, ph;
                        K pk = p.key;
                        if ((ph = p.hash) > h)
                            dir = -1;
                        else if (ph < h)
                            dir = 1;
                        else if ((kc == null &&
                                (kc = comparableClassFor(k)) == null) ||
                                (dir = compareComparables(kc, k, pk)) == 0)
                            dir = tieBreakOrder(k, pk);

                        TreeNode<K, V> xp = p;
                        if ((p = (dir <= 0) ? p.left : p.right) == null) {
                            x.parent = xp;
                            if (dir <= 0)
                                xp.left = x;
                            else
                                xp.right = x;
                            root = balanceInsertion(root, x);
                            break;
                        }
                    }
                }
            }
            moveRootToFront(tab, root);
        }

        /**
         * Returns a list of non-TreeNodes replacing those linked from
         * this node.
         */
        final Node<K, V> untreeify(HashMap<K, V> map) {
            Node<K, V> hd = null, tl = null;
            for (Node<K, V> q = this; q != null; q = q.next) {
                Node<K, V> p = map.replacementNode(q, null);
                if (tl == null)
                    hd = p;
                else
                    tl.next = p;
                tl = p;
            }
            return hd;
        }

        /**
         * Hashmap的put方法在进行操作的时候会，先根据key找到 该元素应该存在数组上的具体位置------table[i]。
         * 其中有一步操作是(p instanceof TreeNode)，也就是判断该节点是不是树形节点。
         * 也就是判断此时，该位置的链表是否已经构建成红黑树。如果是的话将执行putTreeVal(this, tab, hash, key, value)方法
         */
        final TreeNode<K, V> putTreeVal(HashMap<K, V> map, Node<K, V>[] tab, int h, K k, V v) {
            // map  当前Hashmap对象
            // tab  Hashmap对象中的table数组
            // h    hash值
            // K    key
            // V    value
            Class<?> kc = null;
            boolean searched = false;  //标识是否被收索过
            TreeNode<K, V> root = (parent != null) ? root() : this;  // 找到root根节点
            for (TreeNode<K, V> p = root; ; ) {
                int dir, ph;
                K pk;
                if ((ph = p.hash) > h)
                    dir = -1;
                else if (ph < h)
                    dir = 1;
                else if ((pk = p.key) == k || (k != null && k.equals(pk)))
                    return p;
                else if ((kc == null &&
                        (kc = comparableClassFor(k)) == null) ||
                        (dir = compareComparables(kc, k, pk)) == 0) {
                    if (!searched) {
                        TreeNode<K, V> q, ch;
                        searched = true;
                        if (((ch = p.left) != null &&
                                (q = ch.find(h, k, kc)) != null) ||
                                ((ch = p.right) != null &&
                                        (q = ch.find(h, k, kc)) != null))
                            return q;
                    }
                    dir = tieBreakOrder(k, pk);
                }

                TreeNode<K, V> xp = p;
                if ((p = (dir <= 0) ? p.left : p.right) == null) {
                    Node<K, V> xpn = xp.next;
                    TreeNode<K, V> x = map.newTreeNode(h, k, v, xpn);
                    if (dir <= 0)
                        xp.left = x;
                    else
                        xp.right = x;
                    xp.next = x;
                    x.parent = x.prev = xp;
                    if (xpn != null)
                        ((TreeNode<K, V>) xpn).prev = x;
                    moveRootToFront(tab, balanceInsertion(root, x));
                    return null;
                }
            }
        }

        /**
         * Removes the given node, that must be present before this call.
         * This is messier than typical red-black deletion code because we
         * cannot swap the contents of an interior node with a leaf
         * successor that is pinned by "next" pointers that are accessible
         * independently during traversal. So instead we swap the tree
         * linkages. If the current tree appears to have too few nodes,
         * the bin is converted back to a plain bin. (The test triggers
         * somewhere between 2 and 6 nodes, depending on tree structure).
         */
        final void removeTreeNode(HashMap<K, V> map, Node<K, V>[] tab,
                                  boolean movable) {
            int n;
            if (tab == null || (n = tab.length) == 0)
                return;
            int index = (n - 1) & hash;
            TreeNode<K, V> first = (TreeNode<K, V>) tab[index], root = first, rl;
            TreeNode<K, V> succ = (TreeNode<K, V>) next, pred = prev;
            if (pred == null)
                tab[index] = first = succ;
            else
                pred.next = succ;
            if (succ != null)
                succ.prev = pred;
            if (first == null)
                return;
            if (root.parent != null)
                root = root.root();
            if (root == null
                    || (movable
                    && (root.right == null
                    || (rl = root.left) == null
                    || rl.left == null))) {
                tab[index] = first.untreeify(map);  // too small
                return;
            }
            TreeNode<K, V> p = this, pl = left, pr = right, replacement;
            if (pl != null && pr != null) {
                TreeNode<K, V> s = pr, sl;
                while ((sl = s.left) != null) // find successor
                    s = sl;
                boolean c = s.red;
                s.red = p.red;
                p.red = c; // swap colors
                TreeNode<K, V> sr = s.right;
                TreeNode<K, V> pp = p.parent;
                if (s == pr) { // p was s's direct parent
                    p.parent = s;
                    s.right = p;
                } else {
                    TreeNode<K, V> sp = s.parent;
                    if ((p.parent = sp) != null) {
                        if (s == sp.left)
                            sp.left = p;
                        else
                            sp.right = p;
                    }
                    if ((s.right = pr) != null)
                        pr.parent = s;
                }
                p.left = null;
                if ((p.right = sr) != null)
                    sr.parent = p;
                if ((s.left = pl) != null)
                    pl.parent = s;
                if ((s.parent = pp) == null)
                    root = s;
                else if (p == pp.left)
                    pp.left = s;
                else
                    pp.right = s;
                if (sr != null)
                    replacement = sr;
                else
                    replacement = p;
            } else if (pl != null)
                replacement = pl;
            else if (pr != null)
                replacement = pr;
            else
                replacement = p;
            if (replacement != p) {
                TreeNode<K, V> pp = replacement.parent = p.parent;
                if (pp == null)
                    (root = replacement).red = false;
                else if (p == pp.left)
                    pp.left = replacement;
                else
                    pp.right = replacement;
                p.left = p.right = p.parent = null;
            }

            TreeNode<K, V> r = p.red ? root : balanceDeletion(root, replacement);

            if (replacement == p) {  // detach
                TreeNode<K, V> pp = p.parent;
                p.parent = null;
                if (pp != null) {
                    if (p == pp.left)
                        pp.left = null;
                    else if (p == pp.right)
                        pp.right = null;
                }
            }
            if (movable)
                moveRootToFront(tab, r);
        }

        /**
         * Splits nodes in a tree bin into lower and upper tree bins,
         * or untreeifies if now too small. Called only from resize;
         * see above discussion about split bits and indices.
         *
         * @param map   the map
         * @param tab   the table for recording bin heads
         * @param index the index of the table being split
         * @param bit   the bit of hash to split on
         */
        final void split(HashMap<K, V> map, Node<K, V>[] tab, int index, int bit) {
            TreeNode<K, V> b = this;
            // Relink into lo and hi lists, preserving order
            TreeNode<K, V> loHead = null, loTail = null;
            TreeNode<K, V> hiHead = null, hiTail = null;
            int lc = 0, hc = 0;
            for (TreeNode<K, V> e = b, next; e != null; e = next) {
                next = (TreeNode<K, V>) e.next;
                e.next = null;
                if ((e.hash & bit) == 0) {
                    if ((e.prev = loTail) == null)
                        loHead = e;
                    else
                        loTail.next = e;
                    loTail = e;
                    ++lc;
                } else {
                    if ((e.prev = hiTail) == null)
                        hiHead = e;
                    else
                        hiTail.next = e;
                    hiTail = e;
                    ++hc;
                }
            }

            if (loHead != null) {
                if (lc <= UNTREEIFY_THRESHOLD)
                    tab[index] = loHead.untreeify(map);
                else {
                    tab[index] = loHead;
                    if (hiHead != null) // (else is already treeified)
                        loHead.treeify(tab);
                }
            }
            if (hiHead != null) {
                if (hc <= UNTREEIFY_THRESHOLD)
                    tab[index + bit] = hiHead.untreeify(map);
                else {
                    tab[index + bit] = hiHead;
                    if (loHead != null)
                        hiHead.treeify(tab);
                }
            }
        }

        /* ------------------------------------------------------------ */
        // Red-black tree methods, all adapted from CLR


        /***
         *  左旋: 将旋转点的右节点变成其父节点
         *
         *        pp                     pp
         *       /                      /
         *      p                      r
         *     /  \                   / \
         *    pl    r       ==>      p   rr
         *         / \              / \
         *        rl  rr           pl  rl
         */
        static <K, V> TreeNode<K, V> rotateLeft(TreeNode<K, V> root, TreeNode<K, V> p) {
            TreeNode<K, V> r, pp, rl;
            //左旋的前提条件:旋转节点和它的右节点不能为null
            if (p != null && (r = p.right) != null) {
                //将p的右孩子指向r的左节点rl
                if ((rl = p.right = r.left) != null)
                    //如果rl存在，则将rl的父节点指向p
                    rl.parent = p;
                //将r的父节点指向p的父节点 也就是pp
                if ((pp = r.parent = p.parent) == null)
                    //如果此时 r的父节点为null,说明此时r就是根节点(只有根节点才不会有父节点)
                    (root = r).red = false; //根节点为黑色
                    //进这个if块，说明pp不为null,此时要分两种情况：1.p是pp左节点；2.p是pp的右节点
                else if (pp.left == p)
                    //这是第一种情况，p是pp左节点，这是就是将pp的左节点指向r
                    pp.left = r;
                else
                    //这是第二种情况，p是pp右节点，这是就是将pp的右节点指向r
                    pp.right = r;
                //r的左节点指向p
                r.left = p;
                //p的父节点指向r
                p.parent = r;
            }
            return root;
        }

        //右旋
        static <K, V> TreeNode<K, V> rotateRight(TreeNode<K, V> root, TreeNode<K, V> p) {
            TreeNode<K, V> l, pp, lr;
            if (p != null && (l = p.left) != null) {
                if ((lr = p.left = l.right) != null)
                    lr.parent = p;
                if ((pp = l.parent = p.parent) == null)
                    (root = l).red = false;
                else if (pp.right == p)
                    pp.right = l;
                else
                    pp.left = l;
                l.right = p;
                p.parent = l;
            }
            return root;
        }

        static <K, V> TreeNode<K, V> balanceInsertion(TreeNode<K, V> root,
                                                      TreeNode<K, V> x) {
            x.red = true;
            for (TreeNode<K, V> xp, xpp, xppl, xppr; ; ) {
                if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                } else if (!xp.red || (xpp = xp.parent) == null)
                    return root;
                if (xp == (xppl = xpp.left)) {
                    if ((xppr = xpp.right) != null && xppr.red) {
                        xppr.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    } else {
                        if (x == xp.right) {
                            root = rotateLeft(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateRight(root, xpp);
                            }
                        }
                    }
                } else {
                    if (xppl != null && xppl.red) {
                        xppl.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    } else {
                        if (x == xp.left) {
                            root = rotateRight(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateLeft(root, xpp);
                            }
                        }
                    }
                }
            }
        }

        static <K, V> TreeNode<K, V> balanceDeletion(TreeNode<K, V> root,
                                                     TreeNode<K, V> x) {
            for (TreeNode<K, V> xp, xpl, xpr; ; ) {
                if (x == null || x == root)
                    return root;
                else if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                } else if (x.red) {
                    x.red = false;
                    return root;
                } else if ((xpl = xp.left) == x) {
                    if ((xpr = xp.right) != null && xpr.red) {
                        xpr.red = false;
                        xp.red = true;
                        root = rotateLeft(root, xp);
                        xpr = (xp = x.parent) == null ? null : xp.right;
                    }
                    if (xpr == null)
                        x = xp;
                    else {
                        TreeNode<K, V> sl = xpr.left, sr = xpr.right;
                        if ((sr == null || !sr.red) &&
                                (sl == null || !sl.red)) {
                            xpr.red = true;
                            x = xp;
                        } else {
                            if (sr == null || !sr.red) {
                                if (sl != null)
                                    sl.red = false;
                                xpr.red = true;
                                root = rotateRight(root, xpr);
                                xpr = (xp = x.parent) == null ?
                                        null : xp.right;
                            }
                            if (xpr != null) {
                                xpr.red = (xp == null) ? false : xp.red;
                                if ((sr = xpr.right) != null)
                                    sr.red = false;
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateLeft(root, xp);
                            }
                            x = root;
                        }
                    }
                } else { // symmetric
                    if (xpl != null && xpl.red) {
                        xpl.red = false;
                        xp.red = true;
                        root = rotateRight(root, xp);
                        xpl = (xp = x.parent) == null ? null : xp.left;
                    }
                    if (xpl == null)
                        x = xp;
                    else {
                        TreeNode<K, V> sl = xpl.left, sr = xpl.right;
                        if ((sl == null || !sl.red) &&
                                (sr == null || !sr.red)) {
                            xpl.red = true;
                            x = xp;
                        } else {
                            if (sl == null || !sl.red) {
                                if (sr != null)
                                    sr.red = false;
                                xpl.red = true;
                                root = rotateLeft(root, xpl);
                                xpl = (xp = x.parent) == null ?
                                        null : xp.left;
                            }
                            if (xpl != null) {
                                xpl.red = (xp == null) ? false : xp.red;
                                if ((sl = xpl.left) != null)
                                    sl.red = false;
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateRight(root, xp);
                            }
                            x = root;
                        }
                    }
                }
            }
        }


        //递归不变量检查
        static <K, V> boolean checkInvariants(TreeNode<K, V> t) {
            TreeNode<K, V> tp = t.parent, tl = t.left, tr = t.right,
                    tb = t.prev, tn = (TreeNode<K, V>) t.next;
            if (tb != null && tb.next != t)
                return false;
            if (tn != null && tn.prev != t)
                return false;
            if (tp != null && t != tp.left && t != tp.right)
                return false;
            if (tl != null && (tl.parent != t || tl.hash > t.hash))
                return false;
            if (tr != null && (tr.parent != t || tr.hash < t.hash))
                return false;
            if (t.red && tl != null && tl.red && tr != null && tr.red)
                return false;
            if (tl != null && !checkInvariants(tl))
                return false;
            if (tr != null && !checkInvariants(tr))
                return false;
            return true;
        }
    }

}
