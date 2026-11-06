package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import java.io.DataInput
import java.io.DataOutputStream
import java.io.EOFException

/** Reads and writes graph metadata, including optional identity and sidecar binding sections. */
internal object GraphMetadataCodec {
    /** Collect all unique strings from metadata. */
    fun collectMetadataStrings(metadata: GraphMetadata, dest: MutableSet<String>) {
        // Methods
        for ((_, md) in metadata.methods) {
            GraphValueCodec.collectMethodDescriptorStrings(md, dest)
        }

        // Type hierarchy
        for ((typeName, sups) in metadata.supertypes) {
            dest.add(typeName)
            for (s in sups) dest.add(s.className)
        }
        for ((typeName, subs) in metadata.subtypes) {
            dest.add(typeName)
            for (s in subs) dest.add(s.className)
        }

        // Enum values
        for ((key, values) in metadata.enumValues) {
            dest.add(key)
            GraphValueCodec.collectAnyValueStrings(values, dest)
        }

        for ((className, source) in metadata.classOrigins) {
            dest.add(className)
            dest.add(source)
        }

        for ((fromArtifact, dependencies) in metadata.artifactDependencies) {
            dest.add(fromArtifact)
            for ((toArtifact, _) in dependencies) {
                dest.add(toArtifact)
            }
        }

        // Member annotations
        for ((key, annotations) in metadata.memberAnnotations) {
            dest.add(key)
            for ((fqn, attrs) in annotations) {
                dest.add(fqn)
                for ((k, v) in attrs) {
                    dest.add(k)
                    GraphValueCodec.collectAnyValueString(v, dest)
                }
            }
        }

        // Branch scopes
        for (bs in metadata.branchScopes) {
            GraphValueCodec.collectMethodDescriptorStrings(bs.method, dest)
        }

        // Synthetic identities: keys only, fingerprints are raw bytes
        for ((member, _) in metadata.syntheticIdentities) {
            dest.add(member)
        }
    }

    fun saveMetadata(metadata: GraphMetadata, dos: DataOutputStream, strings: StringTable) {
        NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_METADATA)
        // Methods
        dos.writeInt(metadata.methods.size)
        for ((_, md) in metadata.methods) {
            GraphValueCodec.writeMethodDescriptor(dos, md, strings)
        }

        // Type hierarchy: supertypes
        dos.writeInt(metadata.supertypes.size)
        for ((typeName, sups) in metadata.supertypes) {
            dos.writeInt(strings.indexOf(typeName))
            dos.writeInt(sups.size)
            for (s in sups) dos.writeInt(strings.indexOf(s.className))
        }

        // Type hierarchy: subtypes
        dos.writeInt(metadata.subtypes.size)
        for ((typeName, subs) in metadata.subtypes) {
            dos.writeInt(strings.indexOf(typeName))
            dos.writeInt(subs.size)
            for (s in subs) dos.writeInt(strings.indexOf(s.className))
        }

        // Enum values
        dos.writeInt(metadata.enumValues.size)
        for ((key, values) in metadata.enumValues) {
            dos.writeInt(strings.indexOf(key))
            dos.writeInt(values.size)
            for (v in values) GraphValueCodec.writeAnyValue(dos, v, strings)
        }

        dos.writeInt(metadata.classOrigins.size)
        for ((className, source) in metadata.classOrigins) {
            dos.writeInt(strings.indexOf(className))
            dos.writeInt(strings.indexOf(source))
        }

        dos.writeInt(metadata.artifactDependencies.size)
        for ((fromArtifact, dependencies) in metadata.artifactDependencies) {
            dos.writeInt(strings.indexOf(fromArtifact))
            dos.writeInt(dependencies.size)
            for ((toArtifact, weight) in dependencies) {
                dos.writeInt(strings.indexOf(toArtifact))
                dos.writeInt(weight)
            }
        }

        // Member annotations
        dos.writeInt(metadata.memberAnnotations.size)
        for ((key, annotations) in metadata.memberAnnotations) {
            dos.writeInt(strings.indexOf(key))
            dos.writeInt(annotations.size)
            for ((fqn, attrs) in annotations) {
                dos.writeInt(strings.indexOf(fqn))
                dos.writeInt(attrs.size)
                for ((k, v) in attrs) {
                    dos.writeInt(strings.indexOf(k))
                    GraphValueCodec.writeAnyValue(dos, v, strings)
                }
            }
        }

        // Branch scopes
        dos.writeInt(metadata.branchScopes.size)
        for (bs in metadata.branchScopes) {
            dos.writeInt(bs.conditionNodeId)
            GraphValueCodec.writeMethodDescriptor(dos, bs.method, strings)
            dos.writeInt(bs.comparison.operator.ordinal)
            dos.writeInt(bs.comparison.comparandNodeId.value)
            dos.writeInt(bs.trueBranchNodeIds.size)
            for (id in bs.trueBranchNodeIds) dos.writeInt(id)
            dos.writeInt(bs.falseBranchNodeIds.size)
            for (id in bs.falseBranchNodeIds) dos.writeInt(id)
        }
    }

    /**
     * Append the synthetic identity section to `graph.metadata`, after the trailer: readers that predate it stop at the trailer and never
     * see it, the Rust reader ignores everything after the last fixed section. Nothing is written when there is no identity to record, so a
     * graph without synthetic members persists byte-identically to one saved before the section existed.
     *
     * Layout: `int32 header = NodeSerializer.MAGIC_SYNTHETIC_IDENTITIES | NodeSerializer.SYNTHETIC_IDENTITIES_VERSION`, `int32 count`, then
     * per entry `int32 memberStringIndex` and [NodeSerializer.FINGERPRINT_BYTES] raw bytes.
     */
    fun writeSyntheticIdentities(metadata: GraphMetadata, dos: DataOutputStream, strings: StringTable) {
        if (metadata.syntheticIdentities.isEmpty()) return
        dos.writeInt(NodeSerializer.MAGIC_SYNTHETIC_IDENTITIES or NodeSerializer.SYNTHETIC_IDENTITIES_VERSION)
        dos.writeInt(metadata.syntheticIdentities.size)
        // Sorted so the file does not depend on the builder's map order.
        for ((member, fingerprint) in metadata.syntheticIdentities.toSortedMap()) {
            dos.writeInt(strings.indexOf(member))
            dos.write(decodeFingerprint(fingerprint))
        }
    }

    /**
     * Read the optional sections that follow the fixed metadata sections in any order: the trailer that binds `graph.metadata` to its
     * sidecar and the synthetic identities. Reading stops at end of file or at the first header that is neither.
     */
    fun readMetadataOptionalSections(dis: DataInput, strings: StringTable, metadata: GraphMetadata): GraphMetadata {
        var digest: ByteArray? = null
        var ordinalBinding: ByteArray? = null
        var identities: Map<String, String> = emptyMap()
        var header = readOptionalHeader(dis)
        while (header != null) {
            val magic = header and HEADER_MAGIC_MASK
            val version = header and BYTE_MASK
            // A section cut short by a truncated file is dropped as if it were absent: the fixed
            // sections and the node data are intact, so the graph stays readable without it.
            header =
                when {
                    magic == NodeSerializer.MAGIC_METADATA_TRAILER && version == NodeSerializer.BRANCH_DEFINITIONS_VERSION -> {
                        digest = readTrailerDigest(dis)
                        if (digest == null) null else readOptionalHeader(dis)
                    }
                    magic == NodeSerializer.MAGIC_SYNTHETIC_IDENTITIES && version == NodeSerializer.SYNTHETIC_IDENTITIES_VERSION -> {
                        val loaded = readSyntheticIdentities(dis, strings)
                        if (loaded == null) null
                        else {
                            identities = loaded
                            readOptionalHeader(dis)
                        }
                    }
                    magic == NodeSerializer.MAGIC_CALL_SITE_ORDINALS_BINDING &&
                        version == NodeSerializer.CALL_SITE_ORDINALS_BINDING_VERSION -> {
                        ordinalBinding = readTrailerDigest(dis)
                        if (ordinalBinding == null) null else readOptionalHeader(dis)
                    }
                    else -> null
                }
        }
        return metadata.copy(branchDefinitionDigest = digest, syntheticIdentities = identities, callSiteOrdinalDigest = ordinalBinding)
    }

    private fun readTrailerDigest(dis: DataInput): ByteArray? =
        try {
            ByteArray(NodeSerializer.DIGEST_BYTES).also(dis::readFully)
        } catch (_: EOFException) {
            null
        }

    private fun readOptionalHeader(dis: DataInput): Int? =
        try {
            dis.readInt()
        } catch (_: EOFException) {
            null
        }

    /**
     * The section's entries, or `null` when the file ends inside it or its count is not a count. The entries are read in one block and
     * decoded on first use: the metadata is loaded on the first branch-scope access of a mapped graph, which a large corpus holds to a time
     * budget, and a corpus has tens of thousands of entries whose strings nothing may ever look up.
     */
    private fun readSyntheticIdentities(dis: DataInput, strings: StringTable): Map<String, String>? =
        try {
            val count = dis.readInt()
            if (count < 0 || count > NodeSerializer.MAX_SYNTHETIC_IDENTITIES) {
                null
            } else {
                LazySyntheticIdentities(
                    count, ByteArray(count * NodeSerializer.SYNTHETIC_IDENTITY_ENTRY_BYTES).also(dis::readFully), strings)
            }
        } catch (_: EOFException) {
            null
        }

    internal fun encodeFingerprint(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and BYTE_MASK
            chars[index * 2] = NodeSerializer.HEX_DIGITS[value ushr NodeSerializer.NIBBLE_BITS]
            chars[index * 2 + 1] = NodeSerializer.HEX_DIGITS[value and NodeSerializer.NIBBLE_MASK]
        }
        return String(chars)
    }

    internal fun decodeFingerprint(hex: String): ByteArray {
        require(hex.length == NodeSerializer.FINGERPRINT_BYTES * 2) {
            "Expected ${NodeSerializer.FINGERPRINT_BYTES * 2} hex digits, got '$hex'"
        }
        return ByteArray(NodeSerializer.FINGERPRINT_BYTES) { index ->
            val high = Character.digit(hex[index * 2], NodeSerializer.HEX_RADIX)
            val low = Character.digit(hex[index * 2 + 1], NodeSerializer.HEX_RADIX)
            require(high >= 0 && low >= 0) { "Not a hex fingerprint: '$hex'" }
            ((high shl NodeSerializer.HEX_DIGIT_BITS) or low).toByte()
        }
    }

    fun loadMetadata(dis: DataInput, strings: StringTable): GraphMetadata {
        val formatVersion = NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA)
        // Methods
        val methodCount = dis.readInt()
        val methods = mutableMapOf<String, MethodDescriptor>()
        repeat(methodCount) {
            val md = GraphValueCodec.readMethodDescriptor(dis, strings)
            methods[md.identity] = md
        }

        // Type hierarchy: supertypes
        val superCount = dis.readInt()
        val supertypes = mutableMapOf<String, Set<TypeDescriptor>>()
        repeat(superCount) {
            val typeName = strings.get(dis.readInt())
            val count = dis.readInt()
            supertypes[typeName] = (0 until count).map { TypeDescriptor(strings.get(dis.readInt())) }.toSet()
        }

        // Type hierarchy: subtypes
        val subCount = dis.readInt()
        val subtypes = mutableMapOf<String, Set<TypeDescriptor>>()
        repeat(subCount) {
            val typeName = strings.get(dis.readInt())
            val count = dis.readInt()
            subtypes[typeName] = (0 until count).map { TypeDescriptor(strings.get(dis.readInt())) }.toSet()
        }

        // Enum values
        val enumCount = dis.readInt()
        val enumValues = mutableMapOf<String, List<Any?>>()
        repeat(enumCount) {
            val key = strings.get(dis.readInt())
            val count = dis.readInt()
            enumValues[key] = (0 until count).map { GraphValueCodec.readAnyValue(dis, strings, formatVersion) }
        }

        val classOrigins = mutableMapOf<String, String>()
        if (formatVersion >= NodeSerializer.ARTIFACT_METADATA_FORMAT_VERSION) {
            val classOriginCount = dis.readInt()
            repeat(classOriginCount) { classOrigins[strings.get(dis.readInt())] = strings.get(dis.readInt()) }
        }

        val artifactDependencies = mutableMapOf<String, Map<String, Int>>()
        if (formatVersion >= NodeSerializer.ARTIFACT_METADATA_FORMAT_VERSION) {
            val artifactCount = dis.readInt()
            repeat(artifactCount) {
                val fromArtifact = strings.get(dis.readInt())
                val dependencyCount = dis.readInt()
                val dependencies = mutableMapOf<String, Int>()
                repeat(dependencyCount) { dependencies[strings.get(dis.readInt())] = dis.readInt() }
                artifactDependencies[fromArtifact] = dependencies
            }
        }

        // Member annotations
        val annCount = dis.readInt()
        val memberAnnotations = mutableMapOf<String, Map<String, Map<String, Any?>>>()
        repeat(annCount) {
            val key = strings.get(dis.readInt())
            val fqnCount = dis.readInt()
            val annotations = mutableMapOf<String, Map<String, Any?>>()
            repeat(fqnCount) {
                val fqn = strings.get(dis.readInt())
                val kvCount = dis.readInt()
                val kv = mutableMapOf<String, Any?>()
                repeat(kvCount) {
                    val k = strings.get(dis.readInt())
                    val v = GraphValueCodec.readAnnotationValue(dis, strings, formatVersion)
                    kv[k] = v
                }
                annotations[fqn] = kv
            }
            memberAnnotations[key] = annotations
        }

        // Branch scopes
        val scopeCount = dis.readInt()
        val branchScopes =
            (0 until scopeCount).map {
                val condId = dis.readInt()
                val method = GraphValueCodec.readMethodDescriptor(dis, strings)
                val op = ComparisonOp.entries[dis.readInt()]
                val comparandId = dis.readInt()
                val comparison = BranchComparison(op, NodeId(comparandId))
                val trueCount = dis.readInt()
                val trueIds = IntArray(trueCount) { dis.readInt() }
                val falseCount = dis.readInt()
                val falseIds = IntArray(falseCount) { dis.readInt() }
                BranchScopeData(condId, method, comparison, trueIds, falseIds)
            }

        return GraphMetadata(methods, supertypes, subtypes, enumValues, classOrigins, artifactDependencies, memberAnnotations, branchScopes)
    }

    fun readMetadataMethodCount(dis: DataInput): Int {
        NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA)
        return dis.readInt()
    }
}
