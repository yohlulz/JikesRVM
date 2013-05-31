package org.mmtk.plan.concurrent.copy;

import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.mmtk.plan.Plan;
import org.mmtk.plan.Trace;
import org.mmtk.plan.concurrent.Concurrent;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.vmmagic.pragma.Inline;
import org.vmmagic.unboxed.ObjectReference;

public class CMC extends Concurrent {

    public static final int CMC_ALLOC = Plan.ALLOC_DEFAULT;
    static final ConcurrentHashMap<CopySpace, Integer> idBySpace = new ConcurrentHashMap<CopySpace, Integer>();

    private final Trace cmcTrace;

    static {

    }

    public CMC() {
        cmcTrace = new Trace(metaDataSpace);
    }

    public Trace getTrace() {
        return cmcTrace;
    }

    static CopySpace fromSpace() {
        return null;// TODO
    }

    static CopySpace toSpace() {
        return null; // TODO
    }

    static int idForSpace(CopySpace space) {
        return idBySpace.get(space);
    }

    @Override
    @Inline
    public void collectionPhase(short phaseId) {
        // TODO
    }

    @Override
    public boolean willNeverMove(ObjectReference object) {
        for (Entry<CopySpace, Integer> space : idBySpace.entrySet()) {
            if (Space.isInSpace(space.getValue(), object)) {
                return false;
            }
        }
        return super.willNeverMove(object);
    }

    @Override
    public int getPagesUsed() {
        return -1; //TODO
    }

    @Override
    public final int getCollectionReserve() {
        return -1; //TODO
    }

    @Override
    public final int getPagesAvail() {
        return -1;//TODO
    }

}
