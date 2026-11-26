package com.pwnall.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * Every shipped model, held against tools/onnxruntime-android: its operators are in ops.config, the
 * list the phone's ONNX Runtime was built from, and its name is in the README that says what the
 * next config is made from.
 *
 * A kernel that build left out fails on a phone, at session creation, and nowhere else: the JVM
 * tests run the full desktop runtime. This reads the graphs as they are stored. The runtime goes on
 * to fuse some nodes into kernels of its own, which only make_ops_config.py sees, so passing here
 * is necessary and not enough: ShippedModelsTest on a device stays the proof.
 */
class OnnxOpsConfigTest {
    private val tools: File = sequenceOf("../tools", "tools").map(::File).first(File::isDirectory)
        .resolve("onnxruntime-android")
    private val models: List<File> = sequenceOf("src/main/assets", "app/src/main/assets").map(::File)
        .first(File::isDirectory).walk().filter { it.extension == "onnx" }.sortedBy { it.path }.toList()
        .ifEmpty { error("No .onnx where this test looks; fix the path rather than the test.") }

    /** ops.config: the operators built in, by domain and opset. */
    private val built: Map<Pair<String, Long>, Set<String>> = tools.resolve("ops.config").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { line ->
            val (domain, opset, operators) = line.split(';')
            (domain to opset.toLong()) to operators.split(',').toSet()
        }

    /**
     * The build keeps a kernel when the config lists its operator at an opset that kernel serves,
     * so an operator listed at the model's own opset is the kernel the model gets. A model at an
     * opset the config has no line for fails here until the config is made again with it.
     */
    @Test
    fun everyOperatorOfEveryModelIsBuiltIn() {
        models.forEach { model ->
            val opsets = mutableMapOf<String, Long>()
            val used = mutableSetOf<Pair<String, String>>()
            ByteBuffer.wrap(model.readBytes()).fields { number, _, bytes ->
                when (number) {
                    MODEL_OPSET_IMPORT -> opsets += bytes.opset()
                    MODEL_GRAPH -> bytes.operators(used)
                }
            }
            assertEquals(
                "${model.name}, at $opsets, uses operators ops.config does not list there",
                emptyList<Pair<String, String>>(),
                used.filter { (domain, operator) -> operator !in built[domain to opsets[domain]].orEmpty() },
            )
        }
    }

    /** A model the README's list leaves out is a model the next config is made without. */
    @Test
    fun theReadmeNamesEveryModel() {
        val readme = tools.resolve("README.md").readText()
        assertEquals(emptyList<String>(), models.map { it.name }.filterNot { it in readme })
    }

    /**
     * The AAR is built from ONNX Runtime 1.30.0, which by the release notes of 1.30.1 has two faults
     * in memory: a heap overflow where the storage of a packed tensor of elements narrower than a
     * byte is reused for a larger tensor, and a read out of bounds where an input of a Loop or Scan
     * body is also an initializer. So the models are held to having nothing either fault needs: no
     * such tensor, and no Loop or Scan, whose kernels this build leaves out besides. A model that
     * fails here needs an AAR built from 1.30.1 or later; the README beside ops.config says what a
     * rebuild takes.
     */
    @Test
    fun noModelHasWhatTheRuntimeOfTheAarMishandles() {
        assertEquals(emptyList<String>(), built.values.flatten().filter { it == "Loop" || it == "Scan" })
        models.forEach { model ->
            val types = mutableSetOf<Long>()
            ByteBuffer.wrap(model.readBytes()).fields { number, _, bytes ->
                if (number == MODEL_GRAPH) bytes.elementTypes(types)
            }
            assertTrue("${model.name}: its tensors are read", TENSOR_FLOAT in types)
            assertEquals(model.name, emptySet<Long>(), types intersect NARROWER_THAN_A_BYTE)
        }
    }

    /**
     * The element type of every tensor a GraphProto states: of its initializers, of the tensors its
     * nodes carry as attributes, and the one a Cast or a QuantizeLinear is told to make.
     */
    private fun ByteBuffer.elementTypes(into: MutableSet<Long>) {
        fields { number, _, bytes ->
            when (number) {
                GRAPH_INITIALIZER -> bytes.tensorType(into)

                GRAPH_NODE -> bytes.fields { field, _, attribute ->
                    if (field == NODE_ATTRIBUTE) attribute.attributeTypes(into)
                }
            }
        }
    }

    private fun ByteBuffer.attributeTypes(into: MutableSet<Long>) {
        var name = ""
        var number: Long? = null
        fields { field, value, bytes ->
            when (field) {
                ATTRIBUTE_NAME -> name = bytes.text()
                ATTRIBUTE_INT -> number = value
                ATTRIBUTE_TENSOR -> bytes.tensorType(into)
                ATTRIBUTE_GRAPH, ATTRIBUTE_GRAPHS -> bytes.elementTypes(into)
            }
        }
        if (name in TYPE_ATTRIBUTES) number?.let { into += it }
    }

    /** A TensorProto's data_type. */
    private fun ByteBuffer.tensorType(into: MutableSet<Long>) {
        fields { field, value, _ -> if (field == TENSOR_DATA_TYPE) into += value }
    }

    /** An OperatorSetIdProto. */
    private fun ByteBuffer.opset(): Pair<String, Long> {
        var domain = DEFAULT_DOMAIN
        var version = 0L
        fields { field, value, bytes ->
            when (field) {
                OPSET_DOMAIN -> domain = bytes.text().ifEmpty { DEFAULT_DOMAIN }
                OPSET_VERSION -> version = value
            }
        }
        return domain to version
    }

    /** Domain and operator of every node of a GraphProto, and of the graphs If and Loop nodes carry. */
    private fun ByteBuffer.operators(into: MutableSet<Pair<String, String>>) {
        fields { number, _, node ->
            if (number == GRAPH_NODE) {
                var domain = DEFAULT_DOMAIN
                var operator = ""
                node.fields { field, _, bytes ->
                    when (field) {
                        NODE_OP_TYPE -> operator = bytes.text()

                        NODE_DOMAIN -> domain = bytes.text().ifEmpty { DEFAULT_DOMAIN }

                        NODE_ATTRIBUTE -> bytes.fields { attribute, _, graph ->
                            if (attribute == ATTRIBUTE_GRAPH || attribute == ATTRIBUTE_GRAPHS) graph.operators(into)
                        }
                    }
                }
                into += domain to operator
            }
        }
    }

    /**
     * The fields of the protobuf message this buffer holds: [visit] gets a varint's value, or the
     * bytes of a length-delimited field. The weights are skipped where they lie, not copied.
     */
    private fun ByteBuffer.fields(visit: (number: Int, value: Long, bytes: ByteBuffer) -> Unit) {
        while (hasRemaining()) {
            val tag = varint()
            val number = (tag ushr TAG_TYPE_BITS).toInt()
            when ((tag and TAG_TYPE_MASK).toInt()) {
                WIRE_VARINT -> visit(number, varint(), ByteBuffer.allocate(0))

                WIRE_FIXED64 -> position(position() + Long.SIZE_BYTES)

                WIRE_FIXED32 -> position(position() + Int.SIZE_BYTES)

                WIRE_BYTES -> {
                    val length = varint().toInt()
                    visit(number, 0, slice().apply { limit(length) })
                    position(position() + length)
                }

                else -> error("Not a protobuf message this test can read")
            }
        }
    }

    private fun ByteBuffer.varint(): Long {
        var value = 0L
        var shift = 0
        do {
            val byte = get().toInt()
            value = value or ((byte and VARINT_PAYLOAD).toLong() shl shift)
            shift += VARINT_PAYLOAD_BITS
        } while (byte and VARINT_MORE != 0)
        return value
    }

    private fun ByteBuffer.text(): String = Charsets.UTF_8.decode(this).toString()

    // Field numbers from onnx.proto, wire types from the protobuf encoding.
    private companion object {
        const val DEFAULT_DOMAIN = "ai.onnx"
        const val MODEL_GRAPH = 7
        const val MODEL_OPSET_IMPORT = 8
        const val OPSET_DOMAIN = 1
        const val OPSET_VERSION = 2
        const val GRAPH_NODE = 1
        const val GRAPH_INITIALIZER = 5
        const val NODE_OP_TYPE = 4
        const val NODE_ATTRIBUTE = 5
        const val NODE_DOMAIN = 7
        const val ATTRIBUTE_NAME = 1
        const val ATTRIBUTE_INT = 3
        const val ATTRIBUTE_TENSOR = 5
        const val ATTRIBUTE_GRAPH = 6
        const val ATTRIBUTE_GRAPHS = 11
        const val TENSOR_DATA_TYPE = 2

        /** TensorProto.DataType: FLOAT, and UINT4, INT4, FLOAT4E2M1, UINT2 and INT2. */
        const val TENSOR_FLOAT = 1L
        val NARROWER_THAN_A_BYTE = setOf(21L, 22L, 23L, 25L, 26L)

        /** The attributes that name an element type: Cast's, QuantizeLinear's, a generator's. */
        val TYPE_ATTRIBUTES = setOf("to", "output_dtype", "dtype")
        const val TAG_TYPE_BITS = 3
        const val TAG_TYPE_MASK = 7L
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_BYTES = 2
        const val WIRE_FIXED32 = 5
        const val VARINT_PAYLOAD = 0x7F
        const val VARINT_PAYLOAD_BITS = 7
        const val VARINT_MORE = 0x80
    }
}
