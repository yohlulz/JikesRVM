package org.mmtk.plan.concurrent.copy;

import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.mmtk.plan.Plan;
import org.mmtk.plan.Trace;
import org.mmtk.plan.concurrent.Concurrent;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.mmtk.utility.heap.VMRequest;
import org.mmtk.utility.options.Options;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.unboxed.ObjectReference;

import static org.mmtk.plan.concurrent.copy.SpaceState.*;

public class CMC extends Concurrent {

    private static final float MEMORY_FRACTION = 0.65f;
    static final ConcurrentHashMap<CopySpace, AtomicReference<SpaceState>> usedFlagBySpace = new ConcurrentHashMap<CopySpace, AtomicReference<SpaceState>>();

    public static final int CMC_ALLOC = Plan.ALLOC_DEFAULT;
    private static CopySpace fromSpace;
    private static CopySpace toSpace;

    private final Trace cmcTrace;

    static {
        final CopySpace defaultFrom = new CopySpace("cmc-default", true, VMRequest.create());
        usedFlagBySpace.put(defaultFrom, new AtomicReference<SpaceState>(NO_USED));
    }

    public CMC() {
        cmcTrace = new Trace(metaDataSpace);
    }

    @Override
    @Interruptible
    public void processOptions() {
        final int nurseryPages = Options.nurserySize.getMaxNursery();
        final int totalPages = (int) (Space.AVAILABLE_PAGES * MEMORY_FRACTION);

        final int count = (totalPages / nurseryPages) % 17;
        for (int i = 0; i < count; i++) {
            final CopySpace space = new CopySpace("cmc-" + i, true, VMRequest.create());
            usedFlagBySpace.put(space, new AtomicReference<SpaceState>(NO_USED));
        }
    }

    public Trace getTrace() {
        return cmcTrace;
    }

    /**
     * At each call a new mutator space (from space) is calculated
     * 
     * @param triggerNew
     *            Tells if a new space should be considered
     */
    static CopySpace fromSpace(boolean triggerNew) {
        if (triggerNew || fromSpace == null) {
            fromSpace = calculateNewSpace(fromSpace, SpaceState.FROM);
        }
        return fromSpace;
    }

    /**
     * At each call a new collector space (to space) is calculated
     * 
     * @param triggerNew
     *            Tells if a new space should be considered
     */
    static CopySpace toSpace(boolean triggerNew) {
        if (triggerNew || toSpace == null) {
            toSpace = calculateNewSpace(toSpace, SpaceState.TO);
        }
        return toSpace;
    }

    private static CopySpace calculateNewSpace(CopySpace space, SpaceState state) {
        for (Entry<CopySpace, AtomicReference<SpaceState>> flagEntry : usedFlagBySpace.entrySet()) {
            if (flagEntry.getValue().compareAndSet(NO_USED, state)) {
                if (space != null) {
                    if (usedFlagBySpace.get(space).compareAndSet(FROM, NO_USED)
                            || usedFlagBySpace.get(space).compareAndSet(TO, NO_USED)) {
                        return flagEntry.getKey();
                    } else {
                        flagEntry.getValue().set(NO_USED);
                    }
                } else {
                    return flagEntry.getKey();
                }
            }
        }
        return null;
    }

    @Override
    @Inline
    public void collectionPhase(short phaseId) {
        if (phaseId == CMC.PREPARE) {
            super.collectionPhase(phaseId);
            for (Entry<CopySpace, AtomicReference<SpaceState>> spaceEntry : usedFlagBySpace.entrySet()) {
                spaceEntry.getKey().prepare(spaceEntry.getValue().get() == FROM);
            }
            cmcTrace.prepareNonBlocking();
            return;
        }

        if (phaseId == CMC.CLOSURE) {
            cmcTrace.prepareNonBlocking();
            return;
        }

        if (phaseId == CMC.RELEASE) {
            cmcTrace.release();
            for (Entry<CopySpace, AtomicReference<SpaceState>> spaceEntry : usedFlagBySpace.entrySet()) {
                if (spaceEntry.getValue().get() == FROM) {
                    spaceEntry.getKey().release();
                }
            }
        }

        super.collectionPhase(phaseId);
    }

    @Override
    public boolean willNeverMove(ObjectReference object) {
        for (Entry<CopySpace, AtomicReference<SpaceState>> spaceEntry : usedFlagBySpace.entrySet()) {
            if (Space.isInSpace(spaceEntry.getKey().getDescriptor(), object)) {
                return false;
            }
        }
        return super.willNeverMove(object);
    }

    @Override
    public int getPagesUsed() {
        return toSpace(false).reservedPages() + super.getPagesUsed();
    }

    @Override
    public final int getCollectionReserve() {
        return toSpace(false).reservedPages() + super.getCollectionReserve();
    }
}
