/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 * KKEY AdaptiveRecvByteBufAllocator主要用于构建一个最优大小的缓冲区来接收数据。
 * KKEY 比如，在读事件中就会通过该类来获取一个最优大小的的缓冲区来接收对端发送过来的可读取的数据。
 * 以下注释参考引用自：https://cloud.tencent.com/developer/article/1152639
 * RecvByteBufAllocator会根据反馈自动的增加和减少可预测的buffer的大小。
 * 它会逐渐地增加期望的可读到的字节数如果之前的读循环操作所读取到的字节数据已经完全填充满了分配好的buffer
 * (也就是，上一次的读循环操作中执行的所有读取操作所累加的读到的字节数，已经大于等于预测分配的buffer的容量大小，
 * 那么它就会很优雅的自动的去增加可读的字节数量，也就是自动的增加缓冲区的大小 )。
 * 它也会逐渐的减少期望的可读的字节数如果’连续’两次读循环操作后都没有填充满分配的buffer
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;// 默认缓冲区的最小容量大小为64
    static final int DEFAULT_INITIAL = 1024;// 默认缓冲区的容量大小为1024
    static final int DEFAULT_MAXIMUM = 65536;// 默认缓冲区的最大容量大小为65536

    /*
        在调整缓冲区大小时，若是增加缓冲区容量，那么增加的索引值。
        比如，当前缓冲区的大小为SIZE_TABLE[20],若预测下次需要创建的缓冲区需要增加容量大小，
        则新缓冲区的大小为SIZE_TABLE[20 + INDEX_INCREMENT]，即SIZE_TABLE[24]
     */
    private static final int INDEX_INCREMENT = 4;
    /*
        在调整缓冲区大小时，若是减少缓冲区容量，那么减少的索引值。
        比如，当前缓冲区的大小为SIZE_TABLE[20],若预测下次需要创建的缓冲区需要减小容量大小，
        则新缓冲区的大小为SIZE_TABLE[20 - INDEX_DECREMENT]，即SIZE_TABLE[19]
     */
    private static final int INDEX_DECREMENT = 1;

    /*
        用于存储缓冲区容量大小的数组
        SIZE_TABLE为预定义好的以从小到大的顺序设定的可分配缓冲区的大小值的数组。
        因为AdaptiveRecvByteBufAllocator作用是可自动适配每次读事件使用的buffer的大小。
        这样当需要对buffer大小做调整时，只要根据一定逻辑从SIZE_TABLE中取出值，然后根据该值创建新buffer即可。
     */
    private static final int[] SIZE_TABLE;

    private final int minIndex;// 缓冲区最小容量对应于SIZE_TABLE中的下标位置
    private final int maxIndex;// 缓冲区最大容量对应于SIZE_TABLE中的下标位置
    private final int initial;// 缓冲区默认容量大小

    static {
        List<Integer> sizeTable = new ArrayList<Integer>();

        //依次往sizeTable添加元素：[16 , (512-16)]之间16的倍数。即，16、32、48...496
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        //然后再往sizeTable中添加元素：[512 , 512 * (2^N))，N > 1; 直到数值超过Integer的限制(2^31 - 1)；
        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    /*
    因为SIZE_TABLE数组是一个有序数组，因此此处用二分查找法，查找size在SIZE_TABLE中的位置，
    如果size存在于SIZE_TABLE中，则返回对应的索引值；否则返回向上取接近于size大小的SIZE_TABLE数组元素的索引值。
     */
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = getSizeTableIndex(initial);
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            if (bytes == attemptedBytesRead()) {
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        /*
        record方法很重要，它就是完成预测下一个缓冲区容量大小的操作。
        逻辑如下：
        若发现两次，本次读循环真实读取的字节总数 <= ‘SIZE_TABLE[Math.max(0, index - INDEX_DECREMENT - 1)]’ ，则减少预测的缓冲区容量大小。
        重新给成员变量index赋值为为‘index - 1’，若‘index - 1’ < minIndex，则index新值为minIndex。
        根据算出来的新的index索引，给成员变量nextReceiveBufferSize重新赋值'SIZE_TABLE[index]’。
        最后将decreaseNow置位false，该字段用于表示是否有’连续’的两次真实读取的数据满足可减少容量大小的情况。
        注意，这里说的‘连续’并不是真的连续发送，而是指满足条件(即，‘actualReadBytes <= SIZE_TABLE[Math.max(0, index - INDEX_DECREMENT - 1)]’)
        两次的期间没有发生‘actualReadBytes >= nextReceiveBufferSize’的情况。
        若，本次读循环真实读取的字节总数 >= 预测的缓冲区大小，则进行增加预测的缓冲区容量大小。
        新的index为‘index + 4’，若‘index + 4’ > maxIndex，则index新值为maxIndex。
        根据算出来的新的index索引，给成员变量nextReceiveBufferSize重新赋值'SIZE_TABLE[index]’。
        最后将decreaseNow置位false。
         */
        private void record(int actualReadBytes) {
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT - 1)]) {
                if (decreaseNow) {
                    index = max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     // 使用指定的参数创建AdaptiveRecvByteBufAllocator对象。
     // 其中minimum、initial、maximum是正整数。然后通过getSizeTableIndex()方法获取相应容量在SIZE_TABLE中的索引位置。
     // 并将计算出来的索引赋值给相应的成员变量minIndex、maxIndex。同时保证「SIZE_TABLE[minIndex] >= minimum」以及「SIZE_TABLE[maxIndex] <= maximum」.
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }
        //保留最大、最小和初始大小在SIZE_TABLE的索引位置
        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
