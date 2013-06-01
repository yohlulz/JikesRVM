package org.mmtk.plan.concurrent.copy;

import org.mmtk.plan.Plan;
import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.concurrent.ConcurrentCollector;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.LargeObjectLocal;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

public class CMCCollector extends ConcurrentCollector {

    private final CMCTraceLocal cmcTrace;
    private final CopyLocal copySpace;
    private final LargeObjectLocal largeSpace;

    public CMCCollector() {
        copySpace = new CopyLocal();
        cmcTrace = new CMCTraceLocal(global().getTrace(), copySpace);
        largeSpace = new LargeObjectLocal(Plan.loSpace);
    }

    @Inline
    private static CMC global() {
        return (CMC) VM.activePlan.global();
    }

    @Override
    public final TraceLocal getCurrentTrace() {
        return cmcTrace;
    }

    @Override
    protected boolean concurrentTraceComplete() {
        return false;
        //FIXME
        /*
         * let the collectors run until a new collection request is done so all
         * concurrent collectors are aborted
         */
    }

    @Override
    @Inline
    public void postCopy(ObjectReference object, ObjectReference typeRef, int bytes, int allocator) {
        ForwardingWord.clearForwardingBits(object);
        if (allocator == Plan.ALLOC_LOS) {
            Plan.loSpace.initializeHeader(object, false);
        }
    }

    @Override
    @Inline
    public Address allocCopy(ObjectReference original, int bytes, int align, int offset, int allocator) {
        if (allocator == Plan.ALLOC_LOS) {
            if (VM.VERIFY_ASSERTIONS)
                VM.assertions._assert(bytes > Plan.MAX_NON_LOS_COPY_BYTES);
            return largeSpace.alloc(bytes, align, offset);
        } else {
            if (VM.VERIFY_ASSERTIONS) {
                VM.assertions._assert(bytes <= Plan.MAX_NON_LOS_COPY_BYTES);
                VM.assertions._assert(allocator == CMC.CMC_ALLOC);
            }
            return copySpace.alloc(bytes, align, offset);
        }
    }

    @Override
    @Inline
    public void collectionPhase(short phaseId, boolean primary) {
        if (phaseId == CMC.PREPARE) {
            copySpace.rebind(CMC.toSpace(true));
            super.collectionPhase(phaseId, primary);
            largeSpace.prepare(true);
            cmcTrace.prepare();
            return;
        }

        if (phaseId == CMC.CLOSURE) {
            cmcTrace.completeTrace();
            return;
        }

        if (phaseId == CMC.RELEASE) {
            cmcTrace.release();
            largeSpace.release(true);
        }

        super.collectionPhase(phaseId, primary);
    }
}