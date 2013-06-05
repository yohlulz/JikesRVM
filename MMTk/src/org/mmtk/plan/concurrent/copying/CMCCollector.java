package org.mmtk.plan.concurrent.copying;

import org.mmtk.harness.lang.Trace.Item;
import org.mmtk.plan.Plan;
import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.concurrent.ConcurrentCollector;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.LargeObjectLocal;
import org.mmtk.policy.Space;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

import static org.mmtk.harness.lang.Trace.trace;

/**
 * Represents a per-thread collector for the concurrent copying collector.
 *
 * @author Ovidiu Maja
 * @version 03.06.2013
 */
@Uninterruptible
public class CMCCollector extends ConcurrentCollector {

    /**
     * Thread's own trace
     */
    private final CMCTraceLocal cmcTrace;

    /**
     * Per thread copy space.
     */
    private final CopyLocal copySpace;

    /**
     * Large object space used to copy objects directly to it.
     */
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
            Space oldSpace = copySpace.getSpace();
            copySpace.rebind(CMC.calculateNewSpace((CopySpace) copySpace.getSpace(), SpaceState.TO_SPACE));
            trace(Item.DEBUG, "C" + getId() + " - prepare - " + oldSpace + " -> " + copySpace.getSpace());
            largeSpace.prepare(true);
            cmcTrace.prepare();
            super.collectionPhase(phaseId, primary);
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