/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.serialization

import org.jetbrains.kotlin.codegen.state.KotlinTypeMapper
import org.jetbrains.kotlin.descriptors.ClassifierDescriptorWithTypeParameters
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmNameResolver
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.serialization.DescriptorAwareStringTable

class JvmCodegenStringTable @JvmOverloads constructor(
    private val typeMapper: KotlinTypeMapper,
    nameResolver: JvmNameResolver? = null
) : JvmStringTable(nameResolver), DescriptorAwareStringTable {
    override fun getLocalClassIdReplacement(descriptor: ClassifierDescriptorWithTypeParameters): ClassId? {
        val fqName = FqName(typeMapper.mapClass(descriptor).internalName.replace('/', '.'))
        return ClassId(fqName.parent(), FqName.topLevel(fqName.shortName()), true)
    }
}
