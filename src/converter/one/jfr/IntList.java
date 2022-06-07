package one.jfr;

import java.util.Arrays;

public class IntList {

    public int[] list = new int[256];
    public int size;

    public void add(int i) {
        if (size == list.length) {
            list = Arrays.copyOf(list, list.length * 3 / 2);
        }
        list[size++] = i;
    }

}
