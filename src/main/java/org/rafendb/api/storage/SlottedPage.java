package org.rafendb.api.storage;

import java.util.List;
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
public interface SlottedPage {

    /**
     * Formats the header and directory only on a newly allocated empty page.
     */
    public void initialize();

    /**
     * Reserves an invisible slot with the specified byte capacity.
     *
     * @param recordBytes the required byte capacity for the record
     * @return the reserved slot number
     */
    public int reserve(int recordBytes);

    /**
     * Releases a slot reservation without deleting a live record.
     *
     * @param slot the slot number to cancel the reservation for
     */
    public void cancelReservation(int slot);

    /**
     * Writes bytes into a slot without altering its identity, after checking capacity.
     *
     * @param slot the target slot number
     * @param record the byte array to write
     */
    public void write(int slot, byte[] record);

    /**
     * Returns a copy of the record bytes, or empty if the slot does not exist or is deleted.
     *
     * @param slot the slot number to read
     * @return an Optional containing the byte representation of the record, or empty
     */
    public Optional<byte[]> read(int slot);

    /**
     * Deletes the contents of a slot while preserving the identity of other slots.
     *
     * @param slot the slot number to delete
     * @return true if the slot was previously live, false otherwise
     */
    public boolean delete(int slot);

    /**
     * Retrieves a list of live slots in scan order, excluding reservations and deletions.
     *
     * @return a list of live slot numbers
     */
    public List<Integer> liveSlots();

    /**
     * Retrieves the available byte space that can be used after directory overhead.
     *
     * @return the remaining free space in bytes
     */
    public int freeSpace();
}