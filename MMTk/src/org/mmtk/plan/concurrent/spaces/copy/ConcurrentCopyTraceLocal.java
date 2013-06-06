package org.mmtk.plan.concurrent.spaces.copy;

import static org.mmtk.harness.lang.Trace.trace;

import org.mmtk.harness.lang.Trace.Item;
import org.mmtk.plan.Trace;
import org.mmtk.plan.TraceLocal;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.mmtk.utility.HeaderByte;
import org.mmtk.utility.deque.AddressDeque;
import org.mmtk.utility.deque.AddressPairDeque;
import org.mmtk.utility.deque.ObjectReferenceDeque;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

/**
 * Represents the per-thread objects trace.
 * 
 * @author Ovidiu Maja
 * @version 03.06.2013
 * 
 */
@Uninterruptible
public class ConcurrentCopyTraceLocal extends TraceLocal {

    private final CopyLocal copySpace;
    private final ObjectReferenceDeque modbuf;
    private final AddressDeque remset;
    private final AddressPairDeque arrayRemset;

    public ConcurrentCopyTraceLocal(Trace trace, ConcurrentCopyCollector collector) {
        super(trace);
        copySpace = collector.getCopySpace();
        modbuf = collector.getModbuf();
        remset = collector.getRemset();
        arrayRemset = collector.getArrayRemset();
    }

    @Override
    public boolean isLive(ObjectReference object) {
        if (object.isNull()) {
            return false;
        }
        for (Space space : global().getSpaces()) {
            if (Space.isInSpace(space.getDescriptor(), object)) {
                return space.isLive(object);
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
        for (Space space : global().getSpaces()) {
            trace(Item.DEBUG,
                    space.toString() + ": " + Space.isInSpace(space.getDescriptor(), object) + "  "
                            + object.toAddress());
            if (Space.isInSpace(space.getDescriptor(), object)) {
                return ((CopySpace) space).traceObject(this, object, ConcurrentCopy.CC_ALLOC);
            }
        }
        trace(Item.DEBUG, "Last space: " + copySpace.getSpace().toString());
        return super.traceObject(object);
    }

    @Override
    public boolean willNotMoveInCurrentCollection(ObjectReference object) {
        return Space.isInSpace(copySpace.getSpace().getDescriptor(), object);
    }

    @Inline
    private static ConcurrentCopy global() {
        return (ConcurrentCopy) VM.activePlan.global();
    }

    @Override
    @Inline
    protected void processRememberedSets() {
        trace(Item.DEBUG, "Processing modbuf");
        ObjectReference obj;
        while (!(obj = modbuf.pop()).isNull()) {
            if (VM.DEBUG)
                VM.debugging.modbufEntry(obj);
            HeaderByte.markAsUnlogged(obj);
            scanObject(obj);
        }
        trace(Item.DEBUG, "Processing remset");
        while (!remset.isEmpty()) {
            Address loc = remset.pop();
            if (VM.DEBUG)
                VM.debugging.remsetEntry(loc);
            processRootEdge(loc, false);
        }
        trace(Item.DEBUG, "Processing array remset");
        arrayRemset.flushLocal();
        while (!arrayRemset.isEmpty()) {
            Address start = arrayRemset.pop1();
            Address guard = arrayRemset.pop2();
            if (VM.DEBUG)
                VM.debugging.arrayRemsetEntry(start, guard);
            while (start.LT(guard)) {
                processRootEdge(start, false);
                start = start.plus(BYTES_IN_ADDRESS);
            }
        }
    }
}