package org.mmtk.plan.concurrent.copy;

import java.util.Map.Entry;

import org.mmtk.plan.Trace;
import org.mmtk.plan.TraceLocal;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.vmmagic.pragma.Inline;
import org.vmmagic.unboxed.ObjectReference;

public class CMCTraceLocal extends TraceLocal {

    public CMCTraceLocal(Trace trace) {
        super(trace);
    }

    @Override
    public boolean isLive(ObjectReference object) {
        if (object.isNull()) {
            return false;
        }
        for (Entry<CopySpace, Integer> mcEntry : CMC.idBySpace.entrySet()) {
            if (Space.isInSpace(mcEntry.getValue(), object)) {
                return mcEntry.getKey().isLive(object);
            }
        }
        return super.isLive(object);
    }

    @Override
    @Inline
    public ObjectReference traceObject(ObjectReference object) {
        if (object.isNull()) {
            return object;
        }
        for (Entry<CopySpace, Integer> mcEntry : CMC.idBySpace.entrySet()) {
            if (Space.isInSpace(mcEntry.getValue(), object)) {
                return mcEntry.getKey().traceObject(this, object, CMC.CMC_ALLOC);
            }
        }
        return super.traceObject(object);
    }

    @Override
    public boolean willNotMoveInCurrentCollection(ObjectReference object) {
        //FIXME from space
        return !Space.isInSpace(CMC.idForSpace(CMC.fromSpace()), object);
    }
}
