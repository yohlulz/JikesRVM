package org.mmtk.plan.concurrent.spaces.copy;

import static org.mmtk.harness.lang.Trace.trace;

import org.mmtk.harness.lang.Trace.Item;
import org.mmtk.plan.SimpleMutator;
import org.mmtk.plan.concurrent.spaces.state.SpaceState;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.Space;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.utility.deque.WriteBuffer;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * Represents the per-thread mutator state for the concurrent copying collector.
 *
 * @author Ovidiu Maja
 * @version 03.06.2013
 */
@Uninterruptible
public class ConcurrentCopyMutator extends SimpleMutator {

    /**
     * Barrier flag
     */
    public static boolean newMutatorBarrierActive = false;

    /**
     * Barrier flag
     */
    private volatile boolean barrierActive = false;

    /**
     * Remembered set to be used to barrier storage.
     */
    private final WriteBuffer remset;

    /**
     * Per thread copy space used to allocate space.
     */
    private final CopyLocal copySpace;

    @Inline
    private static ConcurrentCopy global() {
        return (ConcurrentCopy) VM.activePlan.global();
    }

    public ConcurrentCopyMutator() {
        barrierActive = newMutatorBarrierActive;

        remset = new WriteBuffer(global().remsetPool);
        copySpace = new CopyLocal();
    }

    @Override
    public void initMutator(int id) {
        super.initMutator(id);
        copySpace.rebind(global().getNewSpaceForState(null, SpaceState.FROM_SPACE));
        trace(Item.DEBUG, "M" + id + " - init - " + copySpace.getSpace());
    }

    @Override
    public final void assertRemsetsFlushed() {
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(remset.isFlushed());
      }
    }

    @Override
    @Inline
    public void collectionPhase(short phaseId, boolean primary) {
        if (phaseId == ConcurrentCopy.SET_BARRIER_ACTIVE) {
            barrierActive = true;
            return;
          }

          if (phaseId == ConcurrentCopy.CLEAR_BARRIER_ACTIVE) {
            barrierActive = false;
            return;
          }

          if (phaseId == ConcurrentCopy.FLUSH_MUTATOR) {
            flush();
            return;
          }

          if (phaseId == ConcurrentCopy.PREPARE) {
              super.collectionPhase(phaseId, primary);
              copySpace.reset();
              flushRememberedSets();
              return;
          }

          if (phaseId == ConcurrentCopy.RELEASE) {
              assertRemsetsFlushed();
              Space oldSpace = copySpace.getSpace();
              copySpace.rebind(global().getNewSpaceForState(copySpace.getSpace(), SpaceState.FROM_SPACE));
              trace(Item.DEBUG, "M" + getId() + "- release - " + oldSpace + " -> " + copySpace.getSpace());
          }

          super.collectionPhase(phaseId, primary);
    }

    @Override
    @Inline
    public Address alloc(int bytes, int align, int offset, int allocator, int site) {
        if (allocator == ConcurrentCopy.CC_ALLOC) {
            return copySpace.alloc(bytes, align, offset);
        }
        return super.alloc(bytes, align, offset, allocator, site);
    }

    @Override
    @Inline
    public void postAlloc(ObjectReference object, ObjectReference typeRef, int bytes, int allocator) {
        if (allocator == ConcurrentCopy.CC_ALLOC) {
            return;
        }
        super.postAlloc(object, typeRef, bytes, allocator);
    }

    @Override
    public Allocator getAllocatorFromSpace(Space space) {
        for (Space fromSpace : global().getSpaces()) {
            if (fromSpace == space) {
                return copySpace;
            }
        }
        return super.getAllocatorFromSpace(space);
    }

    @Override
    public void flushRememberedSets() {
        remset.flushLocal();
        assertRemsetsFlushed();
    }

    @Override
    @Inline
    public final void objectReferenceWrite(ObjectReference src, Address slot, ObjectReference tgt, Word metaDataA, Word metaDataB, int mode) {
        if (barrierActive) {
            barrierProcess(src, slot, tgt, mode);
        }
        VM.barriers.objectReferenceWrite(src, tgt, metaDataA, metaDataB, mode);
    }

    protected void barrierProcess(ObjectReference src, Address slot, ObjectReference tgt, int mode) {
        if (!Space.isInSpace(copySpace.getSpace().getDescriptor(), slot)
                && Space.isInSpace(copySpace.getSpace().getDescriptor(), tgt)) {
            remset.insert(slot);
        }
    }

    @Override
    @Inline
    public boolean objectReferenceTryCompareAndSwap(ObjectReference src, Address slot, ObjectReference old, ObjectReference tgt,
        Word metaDataA, Word metaDataB, int mode) {
        final boolean ok = VM.barriers.objectReferenceTryCompareAndSwap(src, old, tgt, metaDataA, metaDataB, mode);
        if (barrierActive && ok) {
            barrierProcess(src, slot, tgt, mode);
        }
        return ok;
    }

    @Inline
    @Override
    public final boolean objectReferenceBulkCopy(ObjectReference src, Offset srcOffset, ObjectReference dst, Offset dstOffset, int bytes) {
        Address cursor = dst.toAddress().plus(dstOffset);
        final Address limit = cursor.plus(bytes);
        while (barrierActive && cursor.LT(limit)) {
            barrierProcess(src, cursor, cursor.toObjectReference(), 0);
            cursor = cursor.plus(BYTES_IN_ADDRESS);
        }
        return false;
    }

    @Inline
    @Override
    public ObjectReference javaLangReferenceReadBarrier(ObjectReference ref) {
        if (barrierActive) {
            barrierProcess(ref, ref.toAddress(), ref, 0);
        }
        return ref;
    }
}