// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInspection.dataFlow.value;

import com.intellij.codeInspection.dataFlow.interpreter.DataFlowInterpreter;
import com.intellij.codeInspection.dataFlow.lang.ir.DfaInstructionState;
import com.intellij.codeInspection.dataFlow.memory.DfaMemoryState;
import com.intellij.psi.PsiElement;
import com.intellij.util.containers.FList;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A value that could be pushed to the stack and used for control transfer
 */
public final class DfaControlTransferValue extends DfaValue {
  final @NotNull TransferTarget target;
  final @NotNull FList<Trap> traps;

  DfaControlTransferValue(@NotNull DfaValueFactory factory,
                          @NotNull TransferTarget target,
                          @NotNull FList<Trap> traps) {
    super(factory);
    this.traps = traps;
    this.target = target;
  }

  public String toString() {
    return target + (traps.isEmpty() ? "" : " " + traps);
  }

  @Override
  public DfaControlTransferValue bindToFactory(DfaValueFactory factory) {
    return factory.controlTransfer(target, traps);
  }

  public @NotNull TransferTarget getTarget() {
    return target;
  }

  public @NotNull FList<Trap> getTraps() {
    return traps;
  }

  public int @NotNull [] getPossibleTargetIndices() {
    return StreamEx.of(traps).flatCollection(Trap::getPossibleTargets).append(target.getPossibleTargets()).mapToInt(x -> x).toArray();
  }

  /**
   * Represents the target location.
   * TransferTarget should be reusable in another factory
   */
  public interface TransferTarget {
    /**
     * @return list of possible instruction offsets for given target
     */
    default @NotNull Collection<@NotNull Integer> getPossibleTargets() {
      return Collections.emptyList();
    }

    /** 
     * @return next instruction states assuming no traps 
     */
    default @NotNull List<DfaInstructionState> dispatch(DfaMemoryState state, DataFlowInterpreter runner) {
      return Collections.emptyList();
    }
  }

  /**
   * A transfer that returns from the current scope
   */
  public static final TransferTarget RETURN_TRANSFER = new TransferTarget() {
    @Override
    public String toString() {
      return "Return";
    }
  };

  /**
   * Represents traps (e.g. catch sections) that may prevent normal transfer
   */
  public interface Trap {
    /**
     * @return list of possible instruction offsets for given trap
     */
    default @NotNull Collection<@NotNull Integer> getPossibleTargets() {
      return Collections.emptyList();
    }

    /**
     * @return PSI anchor (e.g. catch section)
     */
    @NotNull PsiElement getAnchor();
  }
}
