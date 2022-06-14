/*
 * Copyright 2020 Andrei Pangin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package one.jfr;

/**
 * Fast and compact long->int map.
 */
public class IndexInt {
    private static final int INITIAL_CAPACITY = 16;

    private int[] keys;
    private int[] values;
    private int size;

    public IndexInt() {
        this.keys = new int[INITIAL_CAPACITY];
        this.values = new int[INITIAL_CAPACITY];
    }

    public int index(int key) {
        if (key == 0) {
            throw new NullPointerException();
        }
        int mask = keys.length - 1;
        int i = hashCode(key) & mask;
        while (keys[i] != 0) {
            if (keys[i] == key) {
                return values[i];
            }
            i = (i + 1) & mask;
        }
        keys[i] = key;
        values[i] = ++size;

        if (size * 2 > keys.length) {
            resize(keys.length * 2);
        }
        return size;
    }

    public int shift(int key) {
        if (key == 0) {
            throw new NullPointerException();
        }
        int mask = keys.length - 1;
        int i = hashCode(key) & mask;
        while (keys[i] != 0) {
            if (keys[i] == key) {
                keys[i] = 0;
                for (int j = 0; j < keys.length; j++) {
                    int tKey = keys[j];
                    if (tKey != 0) {
                        if (values[j] > values[i]) {
                            values[j]--;
                        }
                    }
                }
                return values[i];
            }
            i = (i + 1) & mask;
        }
        return 0;
    }

    public int index(int key, int notFound) {
        int mask = keys.length - 1;
        int i = hashCode(key) & mask;
        while (keys[i] != 0) {
            if (keys[i] == key) {
                return values[i];
            }
            i = (i + 1) & mask;
        }
        return notFound;
    }

    public boolean has(int key) {
        return index(key, 0) != 0;
    }

    @SuppressWarnings("unchecked")
    public void orderedKeys(int[] out) {
        for (int i = 0; i < keys.length; i++) {
            int key = keys[i];
            if (key != 0) {
                out[values[i] - 1] = key;
            }
        }
    }

    public int preallocate(int count) {
        if (count * 2 > keys.length) {
            resize(Integer.highestOneBit(count * 4 - 1));
        }
        return count;
    }

    public int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    private void resize(int newCapacity) {
        int[] newKeys = new int[newCapacity];
        int[] newValues = new int[newCapacity];
        int mask = newKeys.length - 1;

        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != 0) {
                for (int j = hashCode(keys[i]) & mask; ; j = (j + 1) & mask) {
                    if (newKeys[j] == 0) {
                        newKeys[j] = keys[i];
                        newValues[j] = values[i];
                        break;
                    }
                }
            }
        }

        keys = newKeys;
        values = newValues;
    }

    private static int hashCode(long key) {
        key *= 0xc6a4a7935bd1e995L;
        return (int) (key ^ (key >>> 32));
    }
}
