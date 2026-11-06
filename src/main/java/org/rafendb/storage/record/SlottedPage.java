package org.rafendb.storage.record;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Page to store records using slots.
 *
 * A SlottedPage is divided into two parts:
 * 1. Slots, stored from the start of the page.
 * Points to the location of the record.
 *
 * 2. Records, stored from the end of the page.
 * The actual data being stored as bytes.
 */
public class SlottedPage {
    public enum SlotStatus {
        FREE(0),
        RESERVED(1),
        LIVE(2);

        public final int value;

        SlotStatus(int value) {
            this.value = value;
        }
    }

    private static final int SIGNATURE = 0x52464442;

    private static final int OFF_SIGNATURE = 0;
    private static final int OFF_SLOT_COUNT = 4;
    private static final int OFF_DATA_START = 8;
    static final int HEADER_SIZE = 12;

    // slot entry layout: offset, length, capacity, status (each an unsigned short)
    static final int SLOT_SIZE = 8;
    private static final int E_OFFSET = 0;
    private static final int E_LENGTH = 2;
    private static final int E_CAPACITY = 4;
    private static final int E_STATUS = 6;

    private static final int MAX_PAGE_SIZE = 65536;

    private final Object lock;
    private final ByteBuffer page;
    private final int pageSize;

    /**
     * Wraps a page buffer (the one returned by BufferManager.pin).
     * The buffer capacity must equal the block size.
     *
     * @param buffer the page bytes
     */
    public SlottedPage(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        int size = buffer.capacity();
        if (size < HEADER_SIZE + SLOT_SIZE + 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("unsupported page size: " + size);
        }
        this.lock = buffer;
        this.page = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        this.page.clear();
        this.pageSize = size;
    }

    /**
     * Returns true if this page has already been formatted by {@link #initialize()}.
     *
     * @return true if the header is present
     */
    public boolean isFormatted() {
        synchronized (lock) {
            return page.getInt(OFF_SIGNATURE) == SIGNATURE;
        }
    }

    /**
     * Formats the header and directory only on a newly allocated empty page.
     *
     * @throws IllegalStateException if the page already has content (nothing is overwritten)
     */
    public void initialize() {
        synchronized (lock) {
            if (page.getInt(OFF_SIGNATURE) != 0) {
                throw new IllegalStateException("page is not empty, refusing to format it");
            }
            page.putInt(OFF_SIGNATURE, SIGNATURE);
            page.putInt(OFF_SLOT_COUNT, 0);
            page.putInt(OFF_DATA_START, pageSize);
        }
    }

    /**
     * Returns the current status of a slot.
     * Slots that do not exist (negative or beyond the directory) are reported as FREE.
     *
     * @param slot the slot number to check the status of.
     * @return a SlotStatus enum (FREE/RESERVED/LIVE) that represents the status of the slot checked.
     */
    public SlotStatus slotStatus(int slot) {
        synchronized (lock) {
            requireFormatted();
            switch (rawStatus(slot)) {
                case 1:
                    return SlotStatus.RESERVED;
                case 2:
                    return SlotStatus.LIVE;
                default:
                    return SlotStatus.FREE;
            }
        }
    }

    /**
     * Reserves an invisible slot with the specified byte capacity.
     * A reservation always gets a brand-new slot number (slots are never reused) and is
     * invisible to read() and liveSlots() until insert() is called on it.
     * If the page is full, nothing is modified and -1 is returned.
     *
     * @param recordBytes the required byte capacity for the record
     * @return the reserved slot number or -1 when page cannot hold the record plus a new slot entry
     * @throws IllegalArgumentException if recordBytes is not positive
     */
    public int reserve(int recordBytes) {
        if (recordBytes <= 0) {
            throw new IllegalArgumentException("recordBytes must be positive: " + recordBytes);
        }
        synchronized (lock) {
            requireFormatted();
            // freeSpace() already substract the free space with slot entry size
            if (recordBytes > freeSpace()) {
                return -1;
            }
            int slot = slotCount();
            int offset = allocate(slot, recordBytes);
            writeEntry(slot, offset, 0, recordBytes, SlotStatus.RESERVED.value);
            return slot;
        }
    }

    /**
     * Reserves a slot with the specified slot number.
     * Any intermediate slots are initialized as FREE.
     *
     * @param recordBytes the required byte capacity for the record
     * @param firstUnusedSlot the slot number to reserve
     * @return the reserved slot number
     */
    public int reserve(int recordBytes, int firstUnusedSlot) {
        if (recordBytes <= 0) {
            throw new IllegalArgumentException("recordBytes must be positive: " + recordBytes);
        }
        if (firstUnusedSlot < 0 || firstUnusedSlot > maxSlotNumber()) {
            throw new IllegalArgumentException("slot out of range: " + firstUnusedSlot);
        }

        synchronized (lock) {
            requireFormatted();

            if (firstUnusedSlot < slotCount()) {
                throw new IllegalArgumentException(
                        "slot has already been used: " + firstUnusedSlot);
            }

            int offset = allocate(firstUnusedSlot, recordBytes);
            writeEntry(firstUnusedSlot, offset, 0, recordBytes, SlotStatus.RESERVED.value);
            return firstUnusedSlot;
        }
    }

    /**
     * Releases a slot reservation without deleting a live record.
     * Does nothing if the slot is not currently RESERVED.
     *
     * @param slot the slot number to cancel the reservation for
     */
    public void cancelReservation(int slot) {
        synchronized (lock) {
            requireFormatted();
            if (rawStatus(slot) != SlotStatus.RESERVED.value) {
                return;
            }
            setU16(entryPos(slot) + E_LENGTH, 0);
            setU16(entryPos(slot) + E_STATUS, SlotStatus.FREE.value);
        }
    }

    /**
     * Stores the record of a new row into a slot obtained from reserve(). The slot becomes LIVE.
     *
     * @param slot the RESERVED slot number
     * @param record the byte array to store
     * @throws IllegalArgumentException if slot is negative/out of range or record is empty
     * @throws IllegalStateException if the slot is not RESERVED, or the page has no room for the
     *         record (it is bigger than the reserved capacity and cannot be moved inside the page)
     */
    public void insert(int slot, byte[] record) {
        writeIfStatus(slot, record, SlotStatus.RESERVED, "insert");
    }

    /**
     * Replaces the record of a LIVE slot. The slot number stays the same. If the record is bigger
     * than the slot capacity, it is moved inside this page when there is room. Otherwise nothing
     * is changed and an exception is thrown, and the caller has to fall back to DELETE + INSERT
     * on another page.
     *
     * @param slot the LIVE slot number
     * @param record the new byte array
     * @throws IllegalArgumentException if slot is negative/out of range or record is empty
     * @throws IllegalStateException if the slot is not LIVE, or the page has no room for the record
     */
    public void update(int slot, byte[] record) {
        writeIfStatus(slot, record, SlotStatus.LIVE, "update");
    }

    /**
     * Recovery only: puts a record back on its original slot (redo of an INSERT, undo of a DELETE).
     * The slot must be FREE, which includes a slot number beyond the current directory; the
     * directory is then extended with FREE slots up to it. The slot becomes LIVE.
     *
     * @param slot the FREE slot number
     * @param record the byte array to store
     * @throws IllegalArgumentException if slot is negative/out of range or record is empty
     * @throws IllegalStateException if the slot is not FREE, or the page has no room for the record
     */
    public void restore(int slot, byte[] record) {
        writeIfStatus(slot, record, SlotStatus.FREE, "restore");
    }

    /**
     * Returns a copy of the record bytes, or empty if the slot does not exist or is deleted.
     * Reserved slots are invisible, so they are empty too.
     *
     * @param slot the slot number to read
     * @return an Optional containing the byte representation of the record, or empty
     */
    public Optional<byte[]> read(int slot) {
        synchronized (lock) {
            requireFormatted();
            if (rawStatus(slot) != SlotStatus.LIVE.value) {
                return Optional.empty();
            }
            int pos = entryPos(slot);
            int offset = u16(pos + E_OFFSET);
            int length = u16(pos + E_LENGTH);
            byte[] out = new byte[length];
            ByteBuffer src = page.duplicate();
            src.clear();
            src.position(offset);
            src.get(out);
            return Optional.of(out);
        }
    }

    /**
     * Deletes the contents of a slot while preserving the identity of other slots.
     * The slot number itself is kept (FREE, never handed out again by reserve).
     * A RESERVED slot is not touched, use cancelReservation for that.
     *
     * @param slot the slot number to delete
     * @return true if the slot was previously live, false otherwise
     */
    public boolean delete(int slot) {
        synchronized (lock) {
            requireFormatted();
            if (rawStatus(slot) != SlotStatus.LIVE.value) {
                return false;
            }
            int pos = entryPos(slot);
            setU16(pos + E_LENGTH, 0);
            setU16(pos + E_STATUS, SlotStatus.FREE.value);
            return true;
        }
    }

    /**
     * Retrieves a list of live slots in scan order, excluding reservations and deletions.
     * Scan order is ascending slot number. The returned list is a snapshot and cannot be modified.
     *
     * @return a list of live slot numbers
     */
    public List<Integer> liveSlots() {
        synchronized (lock) {
            requireFormatted();
            int count = slotCount();
            List<Integer> live = new ArrayList<>();
            for (int s = 0; s < count; s++) {
                if (u16(entryPos(s) + E_STATUS) == SlotStatus.LIVE.value) {
                    live.add(s);
                }
            }
            return Collections.unmodifiableList(live);
        }
    }

    /**
     * Retrieves the available byte space that can be used after directory overhead.
     * This is the largest record that reserve() would accept right now: it counts space that
     * compaction can recover (deleted records, holes) and already subtracts the 8-byte entry a
     * new slot needs.
     *
     * @return the remaining free space in bytes
     */
    public int freeSpace() {
        synchronized (lock) {
            requireFormatted();
            long total = pageSize - directoryEnd(slotCount()) - sumCapacity(-1);
            return (int) Math.max(0, total - SLOT_SIZE);
        }
    }

    /**
     * Retrieves the maximum record size that can be stored in an empty page.
     *
     * @return maximum record size in an empty page
     */
    public int maxRecordSize() {
        return pageSize - HEADER_SIZE - SLOT_SIZE;
    }

    /**
     * Checks the arguments and that the slot is in the expected status, then writes the record,
     * all under one lock so the status cannot change in between.
     */
    private void writeIfStatus(int slot, byte[] record, SlotStatus expected, String operation) {
        Objects.requireNonNull(record, "record");
        if (slot < 0 || slot > maxSlotNumber()) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        if (record.length == 0) {
            throw new IllegalArgumentException("record must not be empty");
        }
        synchronized (lock) {
            requireFormatted();
            SlotStatus actual = slotStatus(slot);
            if (actual != expected) {
                throw new IllegalStateException(
                        operation
                                + " needs slot "
                                + slot
                                + " to be "
                                + expected
                                + " but it is "
                                + actual);
            }
            write(slot, record);
        }
    }

    /**
     * Writes bytes into a slot without altering its identity, after checking capacity, and marks
     * it LIVE. Does not look at the slot status, the callers do that. If the record is bigger than
     * the slot capacity, the record is moved inside this page when there is room (the slot
     * number stays the same). Otherwise nothing is changed and an exception is thrown.
     *
     * @param slot the target slot number
     * @param record the byte array to write
     * @throws IllegalArgumentException if slot is negative/out of range or record is empty
     * @throws IllegalStateException if the page has no room for the record
     */
    private void write(int slot, byte[] record) {
        Objects.requireNonNull(record, "record");
        if (slot < 0 || slot > maxSlotNumber()) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        if (record.length == 0) {
            throw new IllegalArgumentException("record must not be empty");
        }
        synchronized (lock) {
            requireFormatted();
            int capacity = slot < slotCount() ? u16(entryPos(slot) + E_CAPACITY) : 0;
            int offset;
            if (record.length <= capacity) {
                offset = u16(entryPos(slot) + E_OFFSET);
            } else {
                offset = allocate(slot, record.length);
                capacity = record.length;
            }
            ByteBuffer dst = page.duplicate();
            dst.clear();
            dst.position(offset);
            dst.put(record);

            int pos = entryPos(slot);
            setU16(pos + E_OFFSET, offset);
            setU16(pos + E_LENGTH, record.length);
            setU16(pos + E_CAPACITY, capacity);
            setU16(pos + E_STATUS, SlotStatus.LIVE.value);
        }
    }

    /**
     * Makes room for {@code needed} bytes for {@code slot}, growing the directory when the slot
     * is new or beyond the current directory, compacting if the free space is fragmented.
     * Releases the slot's current storage first. Nothing is modified if the room is not there.
     *
     * @return the offset where the caller must put the bytes
     */
    private int allocate(int slot, int needed) {
        if (slot > maxSlotNumber()) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        int count = slotCount();
        int newCount = Math.max(count, slot + 1);
        long newDirEnd = directoryEnd(newCount);
        long available = pageSize - newDirEnd - sumCapacity(slot);
        if (needed > available) {
            throw new IllegalStateException(
                    "page full: need "
                            + needed
                            + " bytes, only "
                            + Math.max(0, available)
                            + " available");
        }

        for (int s = count; s < newCount; s++) {
            writeEntry(s, 0, 0, 0, SlotStatus.FREE.value);
        }
        page.putInt(OFF_SLOT_COUNT, newCount);
        if (slot < count) {
            int pos = entryPos(slot);
            setU16(pos + E_OFFSET, 0);
            setU16(pos + E_CAPACITY, 0);
            setU16(pos + E_LENGTH, 0);
        }

        if (page.getInt(OFF_DATA_START) - newDirEnd < needed) {
            compact();
        }
        int offset = page.getInt(OFF_DATA_START) - needed;
        page.putInt(OFF_DATA_START, offset);
        return offset;
    }

    /**
     * Packs RESERVED/LIVE records against the end of the page. Capacity is reclaimed from FREE slots.
     */
    private void compact() {
        int count = slotCount();
        byte[] snapshot = new byte[pageSize];
        ByteBuffer copy = page.duplicate();
        copy.clear();
        copy.get(snapshot);

        int cursor = pageSize;
        for (int s = 0; s < count; s++) {
            int pos = entryPos(s);
            if (u16(pos + E_STATUS) == SlotStatus.FREE.value) {
                setU16(pos + E_OFFSET, 0);
                setU16(pos + E_LENGTH, 0);
                setU16(pos + E_CAPACITY, 0);
                continue;
            }
            int capacity = u16(pos + E_CAPACITY);
            if (capacity == 0) {
                continue;
            }
            int length = u16(pos + E_LENGTH);
            int oldOffset = u16(pos + E_OFFSET);
            cursor -= capacity;
            if (length > 0) {
                copy.clear();
                copy.position(cursor);
                copy.put(snapshot, oldOffset, length);
            }
            setU16(pos + E_OFFSET, cursor);
        }
        page.putInt(OFF_DATA_START, cursor);
    }

    /** Sum of the capacity of RESERVED and LIVE slots, ignoring {@code exclude}. */
    private long sumCapacity(int exclude) {
        int count = slotCount();
        long sum = 0;
        for (int s = 0; s < count; s++) {
            if (s == exclude) {
                continue;
            }
            int pos = entryPos(s);
            if (u16(pos + E_STATUS) != SlotStatus.FREE.value) {
                sum += u16(pos + E_CAPACITY);
            }
        }
        return sum;
    }

    private void requireFormatted() {
        if (page.getInt(OFF_SIGNATURE) != SIGNATURE) {
            throw new IllegalStateException("page is not initialized");
        }
    }

    private int slotCount() {
        return page.getInt(OFF_SLOT_COUNT);
    }

    private int maxSlotNumber() {
        return (pageSize - HEADER_SIZE) / SLOT_SIZE - 1;
    }

    private static long directoryEnd(int count) {
        return HEADER_SIZE + (long) count * SLOT_SIZE;
    }

    private static int entryPos(int slot) {
        return HEADER_SIZE + slot * SLOT_SIZE;
    }

    private int rawStatus(int slot) {
        if (slot < 0 || slot >= slotCount()) {
            return SlotStatus.FREE.value;
        }
        return u16(entryPos(slot) + E_STATUS);
    }

    private void writeEntry(int slot, int offset, int length, int capacity, int status) {
        int pos = entryPos(slot);
        setU16(pos + E_OFFSET, offset);
        setU16(pos + E_LENGTH, length);
        setU16(pos + E_CAPACITY, capacity);
        setU16(pos + E_STATUS, status);
    }

    private int u16(int index) {
        return page.getShort(index) & 0xFFFF;
    }

    private void setU16(int index, int value) {
        page.putShort(index, (short) value);
    }
}
