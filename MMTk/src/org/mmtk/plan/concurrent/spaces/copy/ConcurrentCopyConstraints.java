package org.mmtk.plan.concurrent.spaces.copy;

import org.mmtk.plan.concurrent.ConcurrentConstraints;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.vmmagic.pragma.Uninterruptible;

import static org.mmtk.plan.concurrent.spaces.state.SpaceManager.*;

/**
 * Represents constraints for a concurrent moving collector.
 * 
 * @author Ovidiu Maja
 * @version 06.06.2013
 */
@Uninterruptible
public class ConcurrentCopyConstraints extends ConcurrentConstraints {

    @Override
    public boolean movesObjects() {
        return true;
    }

    @Override
    public int gcHeaderBits() {
        return CopySpace.LOCAL_GC_BITS_REQUIRED;
    }

    @Override
    public int gcHeaderWords() {
        return CopySpace.GC_HEADER_WORDS_REQUIRED;
    }

    @Override
    public boolean objectReferenceBulkCopySupported() {
        return true;
    }

    @Override
    public int maxNonLOSDefaultAllocBytes() {
        return Space.getFracAvailable(MEMORY_FRACTION / (getNumberOfSpaces() + 1)).toInt();
    }
}