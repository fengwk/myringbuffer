package fun.fengwk.ringbuffer;

import java.util.concurrent.atomic.AtomicLong;

import fun.fengwk.ringbuffer.WaitingQueue.WaitingNode;

/**
 * 环形缓冲阻塞队列。
 * 
 * <p>
 * 通过{@link RingBufferBlockingQueue#enqueue(Object)}入队元素，通过{@link RingBufferBlockingQueue#dequeue()}出队元素。<br/>
 * 当队列已满时调用{@link RingBufferBlockingQueue#enqueue(Object)}的线程将被阻塞，当队列为空时调用{@link RingBufferBlockingQueue#dequeue()}的线程将被阻塞。
 * </p>
 * 
 * <p>
 * 该缓冲队列可以用于生产消费者模式的实现中，可使用命令模式解耦生产消费者，且使用缓冲区能够有效消除生产快消费慢的速度差异。
 * </p>
 * 
 * @author fengwk
 * @since 2020-09-25 12:15
 */
public class RingBufferBlockingQueue<E> {
    
    /**
     * 用于挂起enqueue等待节点。
     */
    private final WaitingQueue full = new WaitingQueue();
    /**
     * 用于挂起dequeue等待节点。
     */
    private final WaitingQueue empty = new WaitingQueue();
    
    /**
     * 记录下一个要写的id（可能还没写完，要配合write使用）
     */
    private final AtomicLong writeIdGen = new AtomicLong();
    /**
     * 记录下一个要读取的id，一旦抢占成功就表明该id所对应的元素已被读取
     */
    private final AtomicLong readIdGen = new AtomicLong();
    
    /**
     * 队列容量
     */
    private final long capacity;
    /**
     * 缓冲区用于保存队列元素
     */
    private final E[] buffer;
    /**
     * 写标记
     */
    private volatile boolean[] write;
    
    /**
     * 
     * @param capacity 队列容量
     */
    @SuppressWarnings("unchecked")
    public RingBufferBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater to zero.");
        }
        this.capacity = capacity;
        this.buffer = (E[]) new Object[capacity];
        this.write = new boolean[capacity];
    }

    /**
     * 入队元素，当队列已满时调用线程将被阻塞。
     *
     * @param element
     * @throws InterruptedException
     */
    public void enqueue(E element) throws InterruptedException {
        for (;;) {
            checkInterrupted();
            // 先读rid再读wid能保证wid总是大于等于rid
            long rid = readIdGen.get();
            long wid = writeIdGen.get();
            // 剩余未读取的元素数量
            long remaining = wid - rid;
            // 由于readIdGen.get()和writeIdGen.get()是两步操作，因此看起来remaining可能大于boundOfSize
            if (remaining >= capacity) {
                for (;;) {
                    WaitingNode wn = full.prepareWait();
                    // 重新检查remaining
                    rid = readIdGen.get();
                    wid = writeIdGen.get();
                    remaining = wid - rid;
                    if (remaining < capacity) {
                        break;
                    }
                    wn.await();
                }
            }
            // 一旦执行到此处必然满足条件remaining小于boundOfSize
            if (remaining >= capacity) {
                // assert
                throw new AssertionError();
            }
            int widIdx = indexOf(wid);
            // 检查当前wid对应位置是否已经被写过，写过的情况分为两种
            // 1.有其它enqueue线程率先抢占了wid进行写操作，直接重试即可
            // 2.当前位置在循环缓存上一轮使用时被写了，由于此时remaining一定小于boundOfSize，因此一定会有dequeue线程擦除原先的写记录，重试等待即可
            // 此处由于有情况1的存在因此不能使用自旋
            if (write[widIdx]) {
                Thread.yield();
                continue;
            }
            // 抢占wid
            if (writeIdGen.compareAndSet(wid, wid+1)) {
                buffer[widIdx] = element;
                write[widIdx] = true;
                // 此前可能有dequeue线程已被(或即将被)挂起了，先进行唤醒
                empty.signal();
                break;
            }
        }
    }

    /**
     * 出队元素，当队列为空时调用线程将被阻塞。
     *
     * @return
     * @throws InterruptedException
     */
    public E dequeue() throws InterruptedException {
        for (;;) {
            checkInterrupted();
            // 先读rid再读wid能保证wid总是大于等于rid
            long rid = readIdGen.get();
            long wid = writeIdGen.get();
            // 剩余未读取的元素数量
            long remaining = wid - rid;
            // 由于readIdGen.get()和writeIdGen.get()是两步操作，但由于rid在wid之前被读取，因此remaining最小只可能为0
            if (remaining == 0) {
                for (;;) {
                    WaitingNode wn = empty.prepareWait();
                    rid = readIdGen.get();
                    wid = writeIdGen.get();
                    remaining = wid - rid;
                    if (remaining > 0) {
                        break;
                    }
                    wn.await();
                }
            }
            // 一旦执行到此处必然满足条件remaining大于0
            if (remaining <= 0) {
                // assert
                throw new AssertionError();
            }
            int ridIdx = indexOf(rid);
            // 检查当前rid对应位置是否还未写过，还未写过的情况分为两种
            // 1.有其它dequeue线程已经抢占了该rid读完后擦去了写标记，直接重试即可。
            // 2.由于一旦执行到此处必然满足条件remaining大于0，则此位置必然已被一个enqueue线程抢占了，该线程正在写入数据还没写完，重试等待即可。
            // 此处由于有情况1的存在因此不能使用自旋。
            if (!write[ridIdx]) {
                Thread.yield();
                continue;
            }
            // 预先读是为了满足只要抢占到rid即读取成功的语义
            E ret = buffer[ridIdx];
            // 抢占rid
            if (readIdGen.compareAndSet(rid, rid+1)) {
                // 这里不使用CAS修改buffer元素是安全的
                // 因为enqueue不能写入一个write为true的位置，所以在设置write[ridIdx]为false之前该位置是不可重写入的，而dequeue无法读取一个尚未enqueue的位置
                buffer[ridIdx] = null;
                write[ridIdx] = false;
                full.signal();
                return ret;
            }
        }
    }

    /**
     * 获取当前队列元素数量。
     *
     * @return
     */
    public int size() {
        // 先读rid再读wid能保证wid总是大于等于rid
        long rid = readIdGen.get();
        long wid = writeIdGen.get();
        // 由于readIdGen.get()和writeIdGen.get()是两步操作，因此看起来remaining可能大于boundOfSize
        return (int) Math.min(wid - rid, capacity) ;
    }

    /**
     * 检查当前队列是否包含元素。
     *
     * @return
     */
    public boolean isEmpty() {
        return size() == 0;
    }
    
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        long rid = readIdGen.get();
        long wid = writeIdGen.get();
        for (; rid < wid; rid++) {
            if (write[indexOf(rid)]) {
                builder.append(buffer[indexOf(rid)]).append(',');
            }
        }
        return (builder.length() > 1 ? builder.substring(0, builder.length() - 1) : builder.toString()) + ']';
    }

    private int indexOf(long id) {
        return (int) (id % capacity);
    }
    
    private void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }
    
}