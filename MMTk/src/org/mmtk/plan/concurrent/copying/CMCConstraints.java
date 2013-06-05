package org.mmtk.plan.concurrent.copying;

import org.mmtk.plan.concurrent.ConcurrentConstraints;
import org.mmtk.policy.CopySpace;

public class CMCConstraints extends ConcurrentConstraints {

    @Override
    public boolean movesObjects() { return true; }

    @Override
    public int gcHeaderBits() { return CopySpace.LOCAL_GC_BITS_REQUIRED; }

    @Override
    public int gcHeaderWords() { return CopySpace.GC_HEADER_WORDS_REQUIRED; }

// FIXME
//    @Override
//    public int maxNonLOSDefaultAllocBytes() { return SegregatedFreeListSpace.MAX_FREELIST_OBJECT_BYTES; }
}
