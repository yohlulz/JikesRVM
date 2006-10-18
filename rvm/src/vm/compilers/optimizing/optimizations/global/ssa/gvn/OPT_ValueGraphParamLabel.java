/*
 * This file is part of Jikes RVM (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright IBM Corp. 2001
 */
//$Id$
package com.ibm.JikesRVM.opt;

/**
 * Represent a value that is a parameter
 *
 * @author Dave Grove
 */
final class OPT_ValueGraphParamLabel {
  private final int paramNum;
  
  OPT_ValueGraphParamLabel(int pn) {
    paramNum = pn;
  }

  public String toString() {
    return "formal"+paramNum;
  }
}
