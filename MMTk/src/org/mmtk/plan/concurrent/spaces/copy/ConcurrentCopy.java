package org.mmtk.plan.concurrent.spaces.copy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.mmtk.plan.Phase;
import org.mmtk.plan.Plan;
import org.mmtk.plan.Trace;
import org.mmtk.plan.concurrent.Concurrent;
import org.mmtk.plan.concurrent.spaces.state.SpaceManager;
import org.mmtk.plan.concurrent.spaces.state.SpaceState;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.mmtk.utility.deque.SharedDeque;
import org.mmtk.utility.heap.VMRequest;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.unboxed.ObjectReference;

import static org.mmtk.plan.concurrent.spaces.state.SpaceState.*;
import static org.mmtk.plan.concurrent.spaces.state.SpaceManager.*;

public class ConcurrentCopy extends Concurrent {

    /**
     * Default allocator id.
     */
    public static final int CC_ALLOC = Plan.ALLOC_DEFAULT;

    public final SharedDeque modbufPool = new SharedDeque("modBufs", metaDataSpace, 1);

    public final SharedDeque remsetPool = new SharedDeque("remSets", metaDataSpace, 1);

    public final SharedDeque arrayRemsetPool = new SharedDeque("arrayRemSets", metaDataSpace, 2);

    /**
     * Global trace.
     */
    private final Trace ccTrace;

    private final SpaceManager spaceManager = new SpaceManager();

    {
        final Space defaultSpace = new CopySpace("cc-default", true, VMRequest.create((1 - MEMORY_FRACTION) / MIN_SPACES, true));
        VM.assertions._assert(spaceManager.addSpace(defaultSpace), "Default space could not be added to space manager.");
    }

    public ConcurrentCopy() {
        ccTrace = new Trace(metaDataSpace);
    }

    /**
     * {@inheritDoc} Allocates the rest of the spaces with respect to the
     * nursery max size
     */
    @Override
    @Interruptible
    public void processOptions() {
        super.processOptions();

        int count = SpaceManager.getNumberOfSpaces();
        for (int i = 0; i < count; i++) {
            final Space space = new CopySpace("cmc-" + i, true, VMRequest.create(MEMORY_FRACTION / (count + 1), true));
            VM.assertions._assert(spaceManager.addSpace(space), "Space " + space + " could not be added.");
        }
    }

    public Trace getTrace() {
        return ccTrace;
    }

    @Override
    @Inline
    public void collectionPhase(short phaseId) {
        if (phaseId == SET_BARRIER_ACTIVE) {
            ConcurrentCopyMutator.newMutatorBarrierActive = true;
            return;
        }

        if (phaseId == CLEAR_BARRIER_ACTIVE) {
            ConcurrentCopyMutator.newMutatorBarrierActive = false;
            return;
        }

        if (phaseId == ConcurrentCopy.PREPARE) {
            super.collectionPhase(phaseId);
            for (Entry<Space, AtomicReference<SpaceState>> spaceEntry : spaceManager.getUsedFlagBySpace().entrySet()) {
                ((CopySpace) spaceEntry.getKey()).prepare(spaceEntry.getValue().get() == FROM_SPACE);
            }
            ccTrace.prepareNonBlocking();
            return;
        }

        if (phaseId == ConcurrentCopy.CLOSURE) {
            ccTrace.prepareNonBlocking();
            return;
        }

        if (phaseId == ConcurrentCopy.RELEASE) {
            ccTrace.release();
            for (Entry<Space, AtomicReference<SpaceState>> spaceEntry : spaceManager.getUsedFlagBySpace().entrySet()) {
                if (spaceEntry.getValue().get() == FROM_SPACE) {
                    ((CopySpace) spaceEntry.getKey()).release();
                }
            }
            modbufPool.clearDeque(1);
            remsetPool.clearDeque(1);
            arrayRemsetPool.clearDeque(2);
        }

        super.collectionPhase(phaseId);
    }

    public CopySpace getNewSpaceForState(Space oldSpace, SpaceState state) {
        return (CopySpace) spaceManager.getNewSpaceForState(oldSpace, state);
    }

    @Inline
    public Set<Entry<Space, AtomicReference<SpaceState>>> getSpaceEntries() {
        return spaceManager.getUsedFlagBySpace().entrySet();
    }

    @Inline
    public List<Space> getSpaces() {
        return new ArrayList<Space>(spaceManager.getUsedFlagBySpace().keySet());
    }

    @Override
    public boolean willNeverMove(ObjectReference object) {
        for (Entry<Space, AtomicReference<SpaceState>> spaceEntry : spaceManager.getUsedFlagBySpace().entrySet()) {
            if (Space.isInSpace(spaceEntry.getKey().getDescriptor(), object)) {
                return false;
            }
        }
        return super.willNeverMove(object);
    }

    private int getReservedPagesForSpaces(SpaceState state) {
        int result = 0;
        for (Entry<Space, AtomicReference<SpaceState>> space : spaceManager.getUsedFlagBySpace().entrySet()) {
            if (space.getValue().get() == state) {
                result += space.getKey().reservedPages();
            }
        }
        return result;
    }

    @Override
    public int getPagesUsed() {
        return getReservedPagesForSpaces(TO_SPACE) + super.getPagesUsed();
    }

    @Override
    public final int getCollectionReserve() {
        return getReservedPagesForSpaces(TO_SPACE) + super.getCollectionReserve();
    }

    @Override
    protected boolean concurrentCollectionRequired() {
        if (!Phase.concurrentPhaseActive()) {
            return true;
        }
        return false;
    }
}