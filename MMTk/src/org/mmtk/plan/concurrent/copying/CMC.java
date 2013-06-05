package org.mmtk.plan.concurrent.copying;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.mmtk.harness.lang.Trace.Item;
import org.mmtk.plan.Plan;
import org.mmtk.plan.Trace;
import org.mmtk.plan.concurrent.Concurrent;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.mmtk.utility.heap.VMRequest;
import org.mmtk.utility.options.Options;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.ObjectReference;

import static org.mmtk.plan.concurrent.copying.SpaceState.*;
import static org.mmtk.harness.lang.Trace.trace;

/**
 * {@inheritDoc}
 * Represents a global state of a concurrent copying collector based on multiple copy spaces that
 * are accessed in parallel from multiple threads.
 * 
 * @author Ovidiu Maja
 * @version 20.03.2013
 */
@Uninterruptible
public class CMC extends Concurrent {

    /**
     * Available memory used fraction.
     */
    private static final float MEMORY_FRACTION = 0.65f;

    /**
     * Lowest number of spaces to be allocated beside the default copy space
     */
    private static final int MIN_SPACES = 2;

    /**
     * Highest number of copy spaces to be allocated beside the default copy space.
     */
    private static final int MAX_SPACES = 19;

    /**
     * Stores each copy space by its corresponding state.
     */
    static final ConcurrentHashMap<CopySpace, AtomicReference<SpaceState>> usedFlagBySpace = new ConcurrentHashMap<>();

    private static final ThreadLocal<SpaceEntryComparator> comparators = new ThreadLocal<SpaceEntryComparator>() {
        @Override
        protected SpaceEntryComparator initialValue() {
            return new SpaceEntryComparator(NOT_USED);
        }
    };

    /**
     * Stores each copy state by its accessed count.
     */
    static final ConcurrentHashMap<CopySpace, AtomicLong> countBySpace = new ConcurrentHashMap<>();

    /**
     * Default allocator id.
     */
    public static final int CMC_ALLOC = Plan.ALLOC_DEFAULT;

    /**
     * Global trace.
     */
    private final Trace cmcTrace;

    /**
     * Default copy space allocation.
     */
    static {
        final CopySpace defaultFrom = new CopySpace("cmc-default", true, VMRequest.create((1 - MEMORY_FRACTION) / MIN_SPACES, true));
        usedFlagBySpace.put(defaultFrom, new AtomicReference<SpaceState>(NOT_USED));
        countBySpace.put(defaultFrom, new AtomicLong());
    }

    public CMC() {
        cmcTrace = new Trace(metaDataSpace);
    }

    /**
     * {@inheritDoc}
     * Allocates the rest of the spaces with respect to the nursery max size
     */
    @Override
    @Interruptible
    public void processOptions() {
        super.processOptions();

        final int nurseryPages = Options.nurserySize.getMaxNursery();
        final int totalPages = (int) (Space.AVAILABLE_PAGES * MEMORY_FRACTION);

        final int count = MIN_SPACES - 1 + (totalPages / nurseryPages) % (MAX_SPACES - MIN_SPACES);
        for (int i = 0; i < count; i++) {
            final CopySpace space = new CopySpace("cmc-" + i, true, VMRequest.create(MEMORY_FRACTION / (count + 1 ), true));
            usedFlagBySpace.put(space, new AtomicReference<SpaceState>(NOT_USED));
            countBySpace.put(space, new AtomicLong());
        }
    }

    public Trace getTrace() {
        return cmcTrace;
    }

    /**
     * Computes a new space based on current statistics.
     * @param space Previous space to be considered when choosing a new space.
     * @param state Current state to be considered when choosing a new space.
     * @return A new chosen space
     */
    static CopySpace calculateNewSpace(CopySpace space, SpaceState state) {
        trace(Item.DEBUG, state.toString() + usedFlagBySpace);
        final Entry<CopySpace, AtomicReference<SpaceState>> consideredEntry = filterSpaces(state);
        if (space != null) {
            if (usedFlagBySpace.get(space).compareAndSet(FROM_SPACE, NOT_USED)
                    || usedFlagBySpace.get(space).compareAndSet(TO_SPACE, NOT_USED)) {
                /* make sure to update the count of a from/to space's state */
                updateSpaceCount(consideredEntry.getKey(), state);
                return consideredEntry.getKey();
            } else {
                /* some other thread updated the space's state so make sure to revert any considered space's state */
                usedFlagBySpace.get(consideredEntry.getKey()).set(consideredEntry.getValue().get());
            }
        } else {
            /* first considered space, just update the space's count */
            updateSpaceCount(consideredEntry.getKey(), state);
            return consideredEntry.getKey();
        }
        trace(Item.DEBUG, "Check CMC space calulation, it returned null.");
        return null;
    }

    private static void updateSpaceCount(CopySpace space, SpaceState state) {
        if (state == TO_SPACE) {
            countBySpace.get(space).incrementAndGet();
        }
        if (state == FROM_SPACE) {
            countBySpace.get(space).decrementAndGet();
        }
    }

    /**
     * Filter all spaces to a space best fit for the desired state.
     * @param state Desired state to filter all spaces by.
     * @return An entry containing the best fit space and the space's old state.
     */
    private static Entry<CopySpace, AtomicReference<SpaceState>> filterSpaces(SpaceState state) {

        final List<Entry<CopySpace, AtomicReference<SpaceState>>> result = new ArrayList<>(usedFlagBySpace.entrySet());
        Collections.sort(result, comparators.get().setState(state));
        /* try to set the new state for the most fit space for the desired state
         * -----------------
         * if one space's state has already been changed -> try the next best fit space for the desired state.
         *  */
        for (Entry<CopySpace, AtomicReference<SpaceState>> entry : result) {
            if (usedFlagBySpace.get(entry.getKey()).compareAndSet(entry.getValue().get(), state)) {
                return entry;
            }
        }
        /* worst case scenario -> all spaces have already been changed, so return the best fit space
         * since we took cpu to filter it and let the caller deal with it.
         * -----------------------
         * for this case to happen, it would need a n concurrency level, where n is the size of usedFlagBySpace and all other threads to
         * successfully change the value for one space.
         *   */
        return result.get(0);
    }

    @Override
    @Inline
    public void collectionPhase(short phaseId) {
        if (phaseId == CMC.PREPARE) {
            super.collectionPhase(phaseId);
            for (Entry<CopySpace, AtomicReference<SpaceState>> spaceEntry : usedFlagBySpace.entrySet()) {
                spaceEntry.getKey().prepare(spaceEntry.getValue().get() == FROM_SPACE);
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
                if (spaceEntry.getValue().get() == FROM_SPACE) {
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
        return getReservedPagesForSpaces(TO_SPACE) + super.getPagesUsed();
    }

    private int getReservedPagesForSpaces(SpaceState state) {
        int result = 0;
        for (Entry<CopySpace, AtomicReference<SpaceState>> space : usedFlagBySpace.entrySet()) {
            if (space.getValue().get() == state) {
                result += space.getKey().reservedPages();
            }
        }
        return result;
    }

    @Override
    public final int getCollectionReserve() {
        return getReservedPagesForSpaces(TO_SPACE) + super.getCollectionReserve();
    }

    /**
     * Represents a comparator for space's state.
     *
     * @author Ovidiu Maja
     * @version 03.05.2013
     */
    private static class SpaceEntryComparator implements Comparator<Entry<CopySpace, AtomicReference<SpaceState>>> {

        private SpaceState state;

        SpaceEntryComparator(SpaceState state) {
            this.state = state;
        }

        SpaceEntryComparator setState(SpaceState state) {
            this.state = state;
            return this;
        }

        @Override
        public int compare(Entry<CopySpace, AtomicReference<SpaceState>> fromEntry, Entry<CopySpace, AtomicReference<SpaceState>> toEntry) {
            final SpaceState from = fromEntry.getValue().get();
            final SpaceState to = toEntry.getValue().get();
            final int result = from.compareTo(to);
            if (result == 0) {
                final long toCount = countBySpace.get(toEntry.getKey()).get();
                final long fromCount = countBySpace.get(fromEntry.getKey()).get();
                return (int) (state == SpaceState.FROM_SPACE ? toCount - fromCount : fromCount - toCount);
            } else {
                return result;
            }
        }
    }
}