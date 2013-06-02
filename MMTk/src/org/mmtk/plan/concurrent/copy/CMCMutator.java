package org.mmtk.plan.concurrent.copy;

import org.mmtk.plan.Plan;
import org.mmtk.plan.TraceWriteBuffer;
import org.mmtk.plan.concurrent.ConcurrentMutator;
import org.mmtk.plan.concurrent.marksweep.CMS;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

public class CMCMutator extends ConcurrentMutator {
    private final CopyLocal copySpace;
    private final TraceWriteBuffer remset;

    public CMCMutator() {
        copySpace = new CopyLocal();
        remset = new TraceWriteBuffer(global().getTrace());
    }

    @Override
    public void initMutator(int id) {
        super.initMutator(id);
        copySpace.rebind(CMC.fromSpace(true));
    }

    @Override
    @Inline
    public Address alloc(int bytes, int align, int offset, int allocator, int site) {
        if (allocator == CMC.CMC_ALLOC) {
            return copySpace.alloc(bytes, align, offset);
        }
        return super.alloc(bytes, align, offset, allocator, site);
    }

    @Override
    @Inline
    public void postAlloc(ObjectReference object, ObjectReference typeRef, int bytes, int allocator) {
        if (allocator == CMC.CMC_ALLOC) {
            return;
        }
        super.postAlloc(object, typeRef, bytes, allocator);
    }

    @Override
    public Allocator getAllocatorFromSpace(Space space) {
        for (CopySpace fromSpace : CMC.usedFlagBySpace.keySet()) {
            if (fromSpace == space) {
                return copySpace;
            }
        }
        return super.getAllocatorFromSpace(space);
    }

    @Override
    @Inline
    public void collectionPhase(short phaseId, boolean primary) {
        if (phaseId == CMC.RELEASE) {
            super.collectionPhase(phaseId, primary);
            copySpace.rebind(CMC.fromSpace(true));
        }
        super.collectionPhase(phaseId, primary);
    }

    @Override
    public void flushRememberedSets() {
        remset.flush();
    }

    @Override
    protected void checkAndEnqueueReference(ObjectReference ref) {
        if (ref.isNull())
            return;
        if (barrierActive) {
            if (!ref.isNull()) {
                if (Space.isInSpace(CMS.MARK_SWEEP, ref))
                    CMS.msSpace.traceObject(remset, ref);
                else if (Space.isInSpace(CMC.IMMORTAL, ref))
                    CMC.immortalSpace.traceObject(remset, ref);
                else if (Space.isInSpace(CMC.LOS, ref))
                    CMC.loSpace.traceObject(remset, ref);
                else if (Space.isInSpace(CMC.NON_MOVING, ref))
                    CMC.nonMovingSpace.traceObject(remset, ref);
                else if (Space.isInSpace(CMC.SMALL_CODE, ref))
                    CMC.smallCodeSpace.traceObject(remset, ref);
                else if (Space.isInSpace(CMC.LARGE_CODE, ref))
                    CMC.largeCodeSpace.traceObject(remset, ref);
            }
        }

        if (VM.VERIFY_ASSERTIONS) {
            if (!ref.isNull() && !Plan.gcInProgress()) {
                if (Space.isInSpace(CMS.MARK_SWEEP, ref))
                    VM.assertions._assert(CMS.msSpace.isLive(ref));
                else if (Space.isInSpace(CMC.IMMORTAL, ref))
                    VM.assertions._assert(CMC.immortalSpace.isLive(ref));
                else if (Space.isInSpace(CMC.LOS, ref))
                    VM.assertions._assert(CMC.loSpace.isLive(ref));
                else if (Space.isInSpace(CMC.NON_MOVING, ref))
                    VM.assertions._assert(CMC.nonMovingSpace.isLive(ref));
                else if (Space.isInSpace(CMC.SMALL_CODE, ref))
                    VM.assertions._assert(CMC.smallCodeSpace.isLive(ref));
                else if (Space.isInSpace(CMC.LARGE_CODE, ref))
                    VM.assertions._assert(CMC.largeCodeSpace.isLive(ref));
            }
        }
    }

    @Inline
    private static CMC global() {
        return (CMC) VM.activePlan.global();
    }
}