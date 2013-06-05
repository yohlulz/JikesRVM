package org.mmtk.plan.concurrent.spaces.state;

import static org.mmtk.harness.lang.Trace.trace;
import static org.mmtk.plan.concurrent.copying.SpaceState.FROM_SPACE;
import static org.mmtk.plan.concurrent.copying.SpaceState.NOT_USED;
import static org.mmtk.plan.concurrent.copying.SpaceState.TO_SPACE;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.mmtk.harness.lang.Trace.Item;
import org.mmtk.plan.concurrent.copying.SpaceState;
import org.mmtk.policy.Space;
import org.mmtk.utility.options.Options;
import org.vmmagic.pragma.Inline;

/**
 * Represents a manager that calculates the fittest space to be considered further on.
 *
 * @author Ovidiu Maja
 * @vesion 05.06.2013
 */
public final class SpaceManager {
    /**
     * Available memory used fraction.
     */
    public static final float MEMORY_FRACTION = 0.65f;
    /**
     * Lowest number of spaces to be allocated beside the default copy space
     */
    public static final int MIN_SPACES = 2;

    /**
     * Highest number of copy spaces to be allocated beside the default copy space.
     */
    public static final int MAX_SPACES = 19;

    /**
     * === Optimization === Represents a per thread comparator used for sorting spaces by type and collection count.
     */
    private static final ThreadLocal<SpaceEntryComparator<Space>> comparators = new ThreadLocal<SpaceEntryComparator<Space>>() {
        @Override
        protected SpaceEntryComparator<Space> initialValue() {
            return new SpaceEntryComparator<Space>();
        }
    };

    /**
     * Stores each space associated with its current state.
     */
    private final ConcurrentHashMap<Space, AtomicReference<SpaceState>> usedFlagBySpace = new ConcurrentHashMap<>();

    /**
     * Stores each space associated with its curent collection count number.
     */
    private final ConcurrentHashMap<Space, AtomicLong> countBySpace = new ConcurrentHashMap<>();

    /**
     * Computes a new space based on current statistics.
     * @param space Previous space to be considered when choosing a new space.
     * @param state Current state to be considered when choosing a new space.
     * @return A new chosen space
     */
    public Space getNewSpaceForState(Space oldSpace, SpaceState state) {
        trace(Item.DEBUG, state.toString() + usedFlagBySpace);
        final Entry<Space, AtomicReference<SpaceState>> consideredEntry = filterSpaces(state);
        if (oldSpace != null) {
            if (usedFlagBySpace.get(oldSpace).compareAndSet(FROM_SPACE, NOT_USED)
                    || usedFlagBySpace.get(oldSpace).compareAndSet(TO_SPACE, NOT_USED)) {
                /* make sure to update the count of a from/to space's state */
                updateSpaceCount(consideredEntry.getKey(), state);
                return consideredEntry.getKey();
            } else {
                /*
                 * some other thread updated the space's state so make sure to
                 * revert any considered space's state
                 */
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

    private void updateSpaceCount(Space space, SpaceState state) {
        if (state == TO_SPACE) {
            countBySpace.get(space).incrementAndGet();
        }
        if (state == FROM_SPACE) {
            countBySpace.get(space).decrementAndGet();
        }
    }

    /**
     * Filter all spaces to a space best fit for the desired state.
     * 
     * @param state
     *            Desired state to filter all spaces by.
     * @return An entry containing the best fit space and the space's old state.
     */
    private Entry<Space, AtomicReference<SpaceState>> filterSpaces(SpaceState state) {

        final List<Entry<Space, AtomicReference<SpaceState>>> result = new ArrayList<>(usedFlagBySpace.entrySet());
        Collections.sort(result, comparators.get().setState(state).setCountBySpace(countBySpace));
        /*
         * try to set the new state for the most fit space for the desired state
         * ----------------- if one space's state has already been changed ->
         * try the next best fit space for the desired state.
         */
        for (Entry<Space, AtomicReference<SpaceState>> entry : result) {
            if (usedFlagBySpace.get(entry.getKey()).compareAndSet(entry.getValue().get(), state)) {
                return entry;
            }
        }
        /*
         * worst case scenario -> all spaces have already been changed, so
         * return the best fit space since we took cpu to filter it and let the
         * caller deal with it. ----------------------- for this case to happen,
         * it would need a n concurrency level, where n is the size of
         * usedFlagBySpace and all other threads to successfully change the
         * value for one space.
         */
        return result.get(0);
    }

    /**
     * Calculates the number of spaces considering a default space.
     * Total number of spaces: getNumberOfSpace + 1 (default space)
     * @return Number of spaces.
     */
    @Inline
    public static float getNumberOfSpaces() {
        final int nurseryPages = Options.nurserySize.getMaxNursery();
        final int totalPages = (int) (Space.AVAILABLE_PAGES * MEMORY_FRACTION);

        return (MIN_SPACES - 1) + (totalPages / nurseryPages) % (MAX_SPACES - MIN_SPACES);
    }
}