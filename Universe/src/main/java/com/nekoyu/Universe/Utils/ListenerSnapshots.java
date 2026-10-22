package com.nekoyu.Universe.Utils;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

/** Copy under the synchronized multimap's lock; invoke user callbacks after releasing it. */
public final class ListenerSnapshots {
    private ListenerSnapshots() {}
    public static <K, V> Multimap<K, V> copy(Multimap<K, V> source) {
        synchronized (source) { return ArrayListMultimap.create(source); }
    }
}
