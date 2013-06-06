package org.mmtk.plan.concurrent.spaces.copy;

import org.mmtk.plan.concurrent.ConcurrentConstraints;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;

import static org.mmtk.plan.concurrent.spaces.state.SpaceManager.*;

public class ConcurrentCopyConstraints extends ConcurrentConstraints {

    @Override
    public boolean movesObjects() { return true; }

    @Override
    public int gcHeaderBits() { return CopySpace.LOCAL_GC_BITS_REQUIRED; }

    @Override
    public int gcHeaderWords() { return CopySpace.GC_HEADER_WORDS_REQUIRED; }

    @Override
    public boolean objectReferenceBulkCopySupported() { return true; }

    /**
     * @return The maximum size of an object that may be allocated directly into the nursery
     */
    @Override
    public int maxNonLOSDefaultAllocBytes() {
        return Space.getFracAvailable(MEMORY_FRACTION / (getNumberOfSpaces() + 1 )).toInt();
    }
}