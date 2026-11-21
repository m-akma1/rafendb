package org.rafendb.processor.operator;

import java.util.ArrayList;

/**
 * Urutan kejadian yang dicatat FakeCcm dan FakeStorage. Kalau dua-duanya dikasih log yang sama,
 * urutan lintas komponen (lock, log, tulis ke SM) bisa dicek dalam satu list.
 */
public final class EventLog extends ArrayList<String> {
    private static final long serialVersionUID = 1L;

    /** Berapa kejadian yang diawali {@code prefix}. */
    public int count(String prefix) {
        return (int) stream().filter(event -> event.startsWith(prefix)).count();
    }

    /** Posisi kejadian pertama yang diawali {@code prefix}, atau -1. */
    public int first(String prefix) {
        for (int i = 0; i < size(); i++) {
            if (get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** Posisi kejadian terakhir yang diawali {@code prefix}, atau -1. */
    public int last(String prefix) {
        for (int i = size() - 1; i >= 0; i--) {
            if (get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}
