package org.mmtk.plan.concurrent.spaces.copy;

import static org.mmtk.harness.lang.Trace.trace;

import org.mmtk.harness.lang.Trace.Item;
import org.mmtk.plan.Plan;
import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.concurrent.ConcurrentCollector;
import org.mmtk.plan.concurrent.spaces.state.SpaceState;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.LargeObjectLocal;
import org.mmtk.policy.Space;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.deque.AddressDeque;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

/**
 * Represents a per-thread collector for the concurrent copying collector.
 *
 * @author Ovidiu Maja
 * @version 03.06.2013
 */
@Uninterruptible
public class ConcurrentCopyCollector extends ConcurrentCollector {

    /**
     * Thread's own trace
     */
    private final ConcurrentCopyTraceLocal ccTrace;

    /**
     * Per thread copy space.
     */
    private final CopyLocal copySpace;

    /**
     * Large object space used to copy objects directly to it.
     */
    private final LargeObjectLocal largeSpace;

    /**
     * Async support for pool and pop of addresses.
     */
    private final AddressDeque remset;

    public ConcurrentCopyCollector() {
        copySpace = new CopyLocal();
        largeSpace = new LargeObjectLocal(Plan.loSpace);

        remset = new AddressDeque("remset", global().remsetPool);
        ccTrace = new ConcurrentCopyTraceLocal(global().getTrace(), this);
    }

    @Override
    public final TraceLocal getCurrentTrace() {
        return ccTrace;
    }

    AddressDeque getRemset() {
        return remset;
    }

    CopyLocal getCopySpace() {
        return copySpace;
    }

    @Override
    protected boolean concurrentTraceComplete() {
        final Space space = copySpace.getSpace();
        if (space == null) {
            return false;
        }
        final Address end = space.getStart().plus(space.getExtent());
        return copySpace.getCursor().EQ(end);
    }

    @Inline
    private static ConcurrentCopy global() {
        return (ConcurrentCopy) VM.activePlan.global();
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
                VM.assertions._assert(allocator == ConcurrentCopy.CC_ALLOC);
            }
            return copySpace.alloc(bytes, align, offset);
        }
    }

    @Override
    @Inline
    public void collectionPhase(short phaseId, boolean primary) {
        if (phaseId == ConcurrentCopy.PREPARE) {
            super.collectionPhase(phaseId, primary);
            largeSpace.prepare(true);
            global().remsetPool.prepareNonBlocking();

            Space oldSpace = copySpace.getSpace();
            copySpace.rebind(global().getNewSpaceForState(copySpace.getSpace(), SpaceState.TO_SPACE));
            trace(Item.DEBUG, "C" + getId() + " - prepare - " + oldSpace + " -> " + copySpace.getSpace());
            ccTrace.prepare();
            return;
        }

        if (phaseId == ConcurrentCopy.CLOSURE) {
            ccTrace.completeTrace();
            return;
        }

        if (phaseId == ConcurrentCopy.RELEASE) {
            ccTrace.release();
            largeSpace.release(true);
            global().remsetPool.reset();
        }

        super.collectionPhase(phaseId, primary);
    }
}