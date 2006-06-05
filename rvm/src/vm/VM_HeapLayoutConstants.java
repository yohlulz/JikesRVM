/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2004
 */
package com.ibm.JikesRVM;

import org.vmmagic.unboxed.*;

/**
 * Constants defining heap layout constants
 *
 * $Id$
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 *
 * @version $Revision$
 * @date $Date$
 */
public interface VM_HeapLayoutConstants {

  /** The address of the start of the data section of the boot image. */
  public static final Address BOOT_IMAGE_DATA_START = 
    //-#if RVM_FOR_32_ADDR
    Address.fromIntZeroExtend
    //-#elif RVM_FOR_64_ADDR
    Address.fromLong
    //-#endif
    (
     //-#value BOOTIMAGE_DATA_ADDRESS
     );

  /** The address of the start of the code section of the boot image. */
  public static final Address BOOT_IMAGE_CODE_START = 
    //-#if RVM_FOR_32_ADDR
    Address.fromIntZeroExtend
    //-#elif RVM_FOR_64_ADDR
    Address.fromLong
    //-#endif
    (
     //-#value BOOTIMAGE_CODE_ADDRESS
     );

  /** The maximum boot image data size */
  public static final int BOOT_IMAGE_DATA_SIZE = 48<<20;

  /** The maximum boot image code size */
  public static final int BOOT_IMAGE_CODE_SIZE = 24<<20;

  /** The address of the end of the data section of the boot image. */
  public static final Address BOOT_IMAGE_DATA_END = BOOT_IMAGE_DATA_START.plus(BOOT_IMAGE_DATA_SIZE);
  /** The address of the end of the code section of the boot image. */
  public static final Address BOOT_IMAGE_CODE_END = BOOT_IMAGE_CODE_START.plus(BOOT_IMAGE_CODE_SIZE);
  /** The address of the end of the boot image. */
  public static final Address BOOT_IMAGE_END = BOOT_IMAGE_CODE_END;

  /** The address in virtual memory that is the highest that can be mapped. */
  public static Address MAXIMUM_MAPPABLE = 
    //-#if RVM_FOR_32_ADDR
    Address.fromIntZeroExtend
    //-#elif RVM_FOR_64_ADDR
    Address.fromLong
    //-#endif
    (
     //-#value MAXIMUM_MAPPABLE_ADDRESS
     );
}
