/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.coroutines

import org.jetbrains.kotlin.codegen.optimization.boxing.isUnitInstance
import org.jetbrains.kotlin.codegen.optimization.common.ControlFlowGraph
import org.jetbrains.kotlin.codegen.optimization.common.asSequence
import org.jetbrains.kotlin.codegen.optimization.common.isMeaningful
import org.jetbrains.kotlin.codegen.optimization.common.removeAll
import org.jetbrains.kotlin.codegen.optimization.transformer.MethodTransformer
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.tree.*

// Inliner emits a lot of locals during inlining.
// Remove all of them since these locals are
//  1) going to be spilled into continuation object
//  2) breaking tail-call elimination
object RedundantLocalsEliminationMethodTransformer : MethodTransformer() {
    override fun transform(internalClassName: String, methodNode: MethodNode) {
        var changed: Boolean
        do {
            changed = removeAloadAstore(methodNode) || removeAconstNullAstore(methodNode) ||
                    removeAloadCheckcastContinuationAstore(methodNode) || removeAloadPop(methodNode) || removeAconstNullPop(methodNode) ||
                    removeGetstaticUnitPop(methodNode) || removeGetstaticUnitAstore(methodNode)
        } while (changed)
    }

    // Replace
    //  GETSTATIC kotlin/Unit.INSTANCE
    //  ASTORE N
    //  ...
    //  ALOAD N
    // with
    //  ...
    //  GETSTATIC kotlin/Unit.INSTANCE
    private fun removeGetstaticUnitAstore(methodNode: MethodNode): Boolean {
        val (getstaticUnit, astore) = findSafeAstorePredecessor(methodNode) { it.isUnitInstance() } ?: return false

        val index = astore.localIndex()
        getstaticUnit as FieldInsnNode

        methodNode.instructions.removeAll(listOf(getstaticUnit, astore))

        methodNode.instructions.asSequence()
            .filter { it.opcode == Opcodes.ALOAD && it.localIndex() == index }
            .toList()
            .forEach {
                methodNode.instructions.set(
                    it,
                    FieldInsnNode(getstaticUnit.opcode, getstaticUnit.owner, getstaticUnit.name, getstaticUnit.desc)
                )
            }
        return true
    }

    // Remove
    //  GETSTATIC kotlin/Unit.INSTANCE
    //  POP
    private fun removeGetstaticUnitPop(methodNode: MethodNode): Boolean {
        val (getstaticUnit, pop) = findPopPredecessor(methodNode) { it.isUnitInstance() } ?: return false

        methodNode.instructions.removeAll(listOf(getstaticUnit, pop))
        return true
    }

    // Remove
    //  ACONST_NULL
    //  POP
    private fun removeAconstNullPop(methodNode: MethodNode): Boolean {
        val (aconstnull, pop) = findPopPredecessor(methodNode) { it.opcode == Opcodes.ACONST_NULL } ?: return false

        methodNode.instructions.removeAll(listOf(aconstnull, pop))
        return true
    }

    // Remove
    //  ALOAD N
    //  POP
    private fun removeAloadPop(methodNode: MethodNode): Boolean {
        val (aload, pop) = findPopPredecessor(methodNode) { it.opcode == Opcodes.ALOAD } ?: return false

        methodNode.instructions.removeAll(listOf(aload, pop))
        return true
    }

    private fun findPopPredecessor(
        methodNode: MethodNode,
        predicate: (AbstractInsnNode) -> Boolean
    ): Pair<AbstractInsnNode, AbstractInsnNode>? {
        val insns = methodNode.instructions.asSequence().filter { predicate(it) }.toList()

        val cfg = ControlFlowGraph.build(methodNode)

        for (insn in insns) {
            val succs = findSuccessorsDFS(insn, cfg, methodNode)
            val succ = succs.singleOrNull()
            if (succ?.opcode == Opcodes.POP) {
                return insn to succ
            }
        }
        return null
    }

    // Replace
    //  ALOAD K
    //  CHECKCAST Continuation
    //  ASTORE N
    //  ...
    //  ALOAD N
    // with
    //  ...
    //  ALOAD K
    //  CHECKCAST Continuation
    private fun removeAloadCheckcastContinuationAstore(methodNode: MethodNode): Boolean {
        val (checkcast, astore) = findSafeAstorePredecessor(methodNode) {
            it.opcode == Opcodes.CHECKCAST &&
                    (it as TypeInsnNode).desc == CONTINUATION_ASM_TYPE.internalName &&
                    it.previous?.opcode == Opcodes.ALOAD
        } ?: return false

        val aload = checkcast.previous
        val continuationIndex = aload.localIndex()
        val index = astore.localIndex()

        methodNode.instructions.removeAll(listOf(aload, checkcast, astore))

        methodNode.instructions.asSequence()
            .filter { it.opcode == Opcodes.ALOAD && it.localIndex() == index }
            .toList()
            .forEach {
                methodNode.instructions.insertBefore(it, VarInsnNode(Opcodes.ALOAD, continuationIndex))
                methodNode.instructions.set(it, TypeInsnNode(Opcodes.CHECKCAST, CONTINUATION_ASM_TYPE.internalName))
            }
        return true
    }

    // Replace
    //  ACONST_NULL
    //  ASTORE N
    //  ...
    //  ALOAD N
    // with
    //  ...
    //  ACONST_NULL
    private fun removeAconstNullAstore(methodNode: MethodNode): Boolean {
        val (aconstnull, astore) = findSafeAstorePredecessor(methodNode) { it.opcode == Opcodes.ACONST_NULL } ?: return false

        val index = astore.localIndex()

        methodNode.instructions.removeAll(listOf(aconstnull, astore))

        methodNode.instructions.asSequence()
            .filter { it.opcode == Opcodes.ALOAD && it.localIndex() == index }
            .toList()
            .forEach { methodNode.instructions.set(it, InsnNode(Opcodes.ACONST_NULL)) }

        return true
    }

    // Replace
    //  ALOAD K
    //  ASTORE N
    //  ...
    //  ALOAD N
    // with
    //  ...
    //  ALOAD K
    private fun removeAloadAstore(methodNode: MethodNode): Boolean {
        val (aload, astore) = findSafeAstorePredecessor(methodNode) { it.opcode == Opcodes.ALOAD } ?: return false

        val index = (astore as VarInsnNode).`var`
        val replacement = (aload as VarInsnNode).`var`

        methodNode.instructions.removeAll(listOf(aload, astore))

        for (insn in methodNode.instructions.asSequence()) {
            if (insn.opcode != Opcodes.ALOAD) continue
            if ((insn as VarInsnNode).`var` != index) continue
            insn.`var` = replacement
        }

        return true
    }

    private fun findSafeAstorePredecessor(
        methodNode: MethodNode,
        predicate: (AbstractInsnNode) -> Boolean
    ): Pair<AbstractInsnNode, AbstractInsnNode>? {
        val insns = methodNode.instructions.asSequence().filter { predicate(it) }.toList()

        val cfg = ControlFlowGraph.build(methodNode)

        for (insn in insns) {
            val succs = findSuccessorsDFS(insn, cfg, methodNode)
            if (succs.size != 1) continue
            val succ = succs.first()
            if (succ.opcode != Opcodes.ASTORE) continue
            val sameLocalAstores = methodNode.instructions.asSequence().filter {
                it.opcode == Opcodes.ASTORE && it.localIndex() == succ.localIndex()
            }.toList()
            if (sameLocalAstores.size != 1) continue
            return insn to succ
        }

        return null
    }

    // Find all meaningful successors of insn
    private fun findSuccessorsDFS(insn: AbstractInsnNode, cfg: ControlFlowGraph, methodNode: MethodNode): Collection<AbstractInsnNode> {
        val visited = hashSetOf<AbstractInsnNode>()

        fun dfs(current: AbstractInsnNode): Collection<AbstractInsnNode> {
            if (!visited.add(current)) return emptySet()

            return cfg.getSuccessorsIndices(current).flatMap {
                val succ = methodNode.instructions[it]
                if (!succ.isMeaningful || succ is JumpInsnNode || succ.opcode == Opcodes.NOP) dfs(succ)
                else setOf(succ)
            }
        }

        return dfs(insn)
    }

    private fun AbstractInsnNode.localIndex(): Int {
        assert(this is VarInsnNode)
        return (this as VarInsnNode).`var`
    }
}