package org.rafendb.mock.storage;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.rafendb.api.storage.SlottedPage;

/** 
 * Mock implementation of SlottedPage returning valid dummy outputs.
 */
public class MockSlottedPage implements SlottedPage {

    @Override
    public void initialize() {}

    @Override
    public int reserve(int recordBytes) {
        return 0;
    }

    @Override
    public void cancelReservation(int slot) {}

    @Override
    public void write(int slot, byte[] record) {}

    @Override
    public Optional<byte[]> read(int slot) {
        return Optional.empty();
    }

    @Override
    public boolean delete(int slot) {
        return false;
    }

    @Override
    public List<Integer> liveSlots() {
        return Collections.emptyList();
    }

    @Override
    public int freeSpace() {
        return 4096;
    }
}