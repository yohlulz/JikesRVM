package org.mmtk.plan.concurrent.copy;

import org.mmtk.harness.lang.Trace.Item;
import org.mmtk.plan.Plan;
import org.mmtk.plan.TraceWriteBuffer;
import org.mmtk.plan.concurrent.ConcurrentMutator;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

import static org.mmtk.harness.lang.Trace.trace;

/**
 * Represents the per-thread mutator state for the concurrent copying collector.
 *
 * @author Ovidiu Maja
 * @version 03.06.2013
 */
@Uninterruptible
public class CMCMutator extends ConcurrentMutator {

    /**
     * Per thread copy space used to allocate space.
     */
    private final CopyLocal copySpace;

    /**
     * Remembered sets.
     */
    private final TraceWriteBuffer remset;

    public CMCMutator() {
        copySpace = new CopyLocal();
        remset = new TraceWriteBuffer(global().getTrace());
    }

    @Override
    public void initMutator(int id) {
        super.initMutator(id);
        copySpace.rebind(CMC.calculateNewSpace(null, SpaceState.FROM_SPACE));
        trace(Item.DEBUG, "M" + id + " - init - " + copySpace.getSpace());
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
            Space oldSpace = copySpace.getSpace();
            copySpace.rebind(CMC.calculateNewSpace((CopySpace) copySpace.getSpace(), SpaceState.FROM_SPACE));
            trace(Item.DEBUG, "M" + getId() + "- release - " + oldSpace + " -> " + copySpace.getSpace());
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
                for (CopySpace space : CMC.usedFlagBySpace.keySet()) {
                    if (Space.isInSpace(space.getDescriptor(), ref)) {
                        space.traceObject(remset, ref, CMC.CMC_ALLOC);
                    }
                }
                if (Space.isInSpace(CMC.IMMORTAL, ref))
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
                for (CopySpace space : CMC.usedFlagBySpace.keySet()) {
                    if (Space.isInSpace(space.getDescriptor(), ref)) {
                        VM.assertions._assert(space.isLive(ref));
                    }
                }
                if (Space.isInSpace(CMC.IMMORTAL, ref))
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