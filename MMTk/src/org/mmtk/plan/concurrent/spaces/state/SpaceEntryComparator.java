package org.mmtk.plan.concurrent.spaces.state;

import java.util.Comparator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.mmtk.policy.Space;

/**
 * Represents a comparator for space's state.
 * 
 * @author Ovidiu Maja
 * @version 03.05.2013
 */
public class SpaceEntryComparator<T extends Space> implements Comparator<Entry<T, AtomicReference<SpaceState>>> {

    /**
     * Represents the current state to be compared against the considered space's.
     */
    private SpaceState state;

    /**
     * Stores each space and its current collection count number.
     */
    private ConcurrentHashMap<T, AtomicLong> countBySpace;

    public SpaceEntryComparator() {
        this.state = SpaceState.NOT_USED;
        countBySpace = new ConcurrentHashMap<T, AtomicLong>();
    }

    public SpaceEntryComparator<T> setState(SpaceState state) {
        this.state = state;
        return this;
    }

    public SpaceEntryComparator<T> setCountBySpace(ConcurrentHashMap<T, AtomicLong> countBySpace) {
        this.countBySpace = countBySpace;
        return this;
    }

    @Override
    public int compare(Entry<T, AtomicReference<SpaceState>> fromEntry, Entry<T, AtomicReference<SpaceState>> toEntry) {
        final SpaceState from = fromEntry.getValue().get();
        final SpaceState to = toEntry.getValue().get();
        final int result = from.compareTo(to);

        if (result == 0) {
            final long toCount = countBySpace.get(toEntry.getKey()).get();
            final long fromCount = countBySpace.get(fromEntry.getKey()).get();
            /*
             * M : return the space with the highest count
             *      - represents the space used the most number of time as a TO_SPACE
             *      - has the most chances to find some garbage objects
             *      - toCount - fromCount => sorts the spaces by highest count first
             *      
             * C: return the space with the lowest count
             *      - represents the space used the most number of time as a FROM_SPACE
             *      - has the most chances to be mostly empty or or have a small amount of objects
             *      - fromCount - toCount => sorts the spaces by lowest count first
             * */
            return (int) (state == SpaceState.FROM_SPACE ? toCount - fromCount : fromCount - toCount);
        } else {
            return result;
        }
    }
}