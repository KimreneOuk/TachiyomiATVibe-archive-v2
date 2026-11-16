package eu.kanade.translation.models.imports

import java.io.File
import java.io.IOException

/**
 * Reads real ONNX artifact tensor metadata (graph inputs/outputs) from the
 * protobuf wire format, skipping unknown fields. This is the supported parser
 * behind L1 for ONNX; TFLite has no parser in this prototype, so its L1 stays
 * UNVERIFIED.
 */
object OnnxTensorMetadataParser {

    class ParseException(message: String) : IOException(message)

    /**
     * Parser implementation bound, NOT a device capability limit and NOT a
     * probe limit: metadata parsing loads the graph into memory, so artifacts
     * above this are rejected with an honest unsupported state until a
     * bounded streaming parser ships. Revisit deliberately, not silently.
     */
    const val MAX_PARSE_BYTES: Long = 256L * 1024L * 1024L

    data class ParsedTensor(val name: String, val shape: List<Long?>, val dtype: String)

    data class ParsedIo(val inputs: List<ParsedTensor>, val outputs: List<ParsedTensor>, val externalDataDetected: Boolean)

    /** Skipped fields (e.g. multi-GB raw_data) advance without allocation; the graph itself is bounded by [MAX_PARSE_BYTES]. */
    fun requireParsableSize(sizeBytes: Long, name: String) {
        if (sizeBytes > MAX_PARSE_BYTES) {
            throw ParseException(
                "ONNX artifact $name is $sizeBytes bytes; metadata parsing supports up to $MAX_PARSE_BYTES " +
                    "bytes in this build (streaming parser not implemented)",
            )
        }
    }

    fun parse(file: File): ParsedIo {
        requireParsableSize(file.length(), file.name)
        return parse(file.readBytes())
    }

    fun parse(bytes: ByteArray): ParsedIo {
        requireParsableSize(bytes.size.toLong(), "<memory>")
        val cursor = ProtobufReader(bytes)
        var graph: ByteArray? = null
        while (!cursor.exhausted()) {
            val (field, wire) = cursor.readTag()
            when {
                field == 7 && wire == ProtobufReader.WIRE_LEN -> graph = cursor.readLengthDelimited()
                wire == ProtobufReader.WIRE_VARINT -> cursor.skipVarint()
                wire == ProtobufReader.WIRE_LEN -> cursor.skipLengthDelimited()
                wire == ProtobufReader.WIRE_FIXED32 -> cursor.skipFixed32()
                wire == ProtobufReader.WIRE_FIXED64 -> cursor.skipFixed64()
                else -> throw ParseException("Unsupported wire type $wire at model level")
            }
        }
        val graphBytes = graph ?: throw ParseException("No GraphProto (field 7) found — not an ONNX model file")
        return parseGraph(graphBytes)
    }

    private fun parseGraph(bytes: ByteArray): ParsedIo {
        val cursor = ProtobufReader(bytes)
        val inputs = ArrayList<ParsedTensor>()
        val outputs = ArrayList<ParsedTensor>()
        var external = false
        while (!cursor.exhausted()) {
            val (field, wire) = cursor.readTag()
            when {
                field == 11 && wire == ProtobufReader.WIRE_LEN -> inputs += parseValueInfo(cursor.readLengthDelimited())
                field == 12 && wire == ProtobufReader.WIRE_LEN -> outputs += parseValueInfo(cursor.readLengthDelimited())
                field == 5 && wire == ProtobufReader.WIRE_LEN -> external = scanInitializer(cursor.readLengthDelimited()) || external
                field == 15 && wire == ProtobufReader.WIRE_LEN -> external = scanSparse(cursor.readLengthDelimited()) || external
                wire == ProtobufReader.WIRE_VARINT -> cursor.skipVarint()
                wire == ProtobufReader.WIRE_LEN -> cursor.skipLengthDelimited()
                wire == ProtobufReader.WIRE_FIXED32 -> cursor.skipFixed32()
                wire == ProtobufReader.WIRE_FIXED64 -> cursor.skipFixed64()
                else -> throw ParseException("Unsupported wire type $wire at graph level")
            }
        }
        return ParsedIo(inputs, outputs, external)
    }

    /**
     * TensorProto scan for external weight references. Even metadata-only loads
     * make ORT resolve external files relative to the model path, so an
     * external reference is a hard rejection, not an unchecked pass.
     */
    private fun scanInitializer(bytes: ByteArray): Boolean {
        val cursor = ProtobufReader(bytes)
        var external = false
        while (!cursor.exhausted()) {
            val (field, wire) = cursor.readTag()
            when {
                field == 10 && wire == ProtobufReader.WIRE_LEN -> {
                    external = true
                    cursor.skipLengthDelimited()
                }
                field == 11 && wire == ProtobufReader.WIRE_VARINT -> {
                    if (cursor.readVarint() == 1L) external = true
                }
                wire == ProtobufReader.WIRE_VARINT -> cursor.skipVarint()
                wire == ProtobufReader.WIRE_LEN -> cursor.skipLengthDelimited()
                wire == ProtobufReader.WIRE_FIXED32 -> cursor.skipFixed32()
                wire == ProtobufReader.WIRE_FIXED64 -> cursor.skipFixed64()
                else -> throw ParseException("Unsupported wire type $wire in TensorProto")
            }
        }
        return external
    }

    /** SparseTensorProto: values (1) and indices (2) are TensorProtos. */
    private fun scanSparse(bytes: ByteArray): Boolean {
        val cursor = ProtobufReader(bytes)
        var external = false
        while (!cursor.exhausted()) {
            val (field, wire) = cursor.readTag()
            when {
                (field == 1 || field == 2) && wire == ProtobufReader.WIRE_LEN ->
                    external = scanInitializer(cursor.readLengthDelimited()) || external
                wire == ProtobufReader.WIRE_VARINT -> cursor.skipVarint()
                wire == ProtobufReader.WIRE_LEN -> cursor.skipLengthDelimited()
                wire == ProtobufReader.WIRE_FIXED32 -> cursor.skipFixed32()
                wire == ProtobufReader.WIRE_FIXED64 -> cursor.skipFixed64()
                else -> throw ParseException("Unsupported wire type $wire in SparseTensorProto")
            }
        }
        return external
    }

    private fun parseValueInfo(bytes: ByteArray): ParsedTensor {
        val cursor = ProtobufReader(bytes)
        var name: String? = null
        var tensor: ParsedTensor? = null
        while (!cursor.exhausted()) {
            val (field, wire) = cursor.readTag()
            when {
                field == 1 && wire == ProtobufReader.WIRE_LEN -> name = cursor.readString()
                field == 2 && wire == ProtobufReader.WIRE_LEN -> tensor = parseType(cursor.readLengthDelimited())
                wire == ProtobufReader.WIRE_VARINT -> cursor.skipVarint()
                wire == ProtobufReader.WIRE_LEN -> cursor.skipLengthDelimited()
                wire == ProtobufReader.WIRE_FIXED32 -> cursor.skipFixed32()
                wire == ProtobufReader.WIRE_FIXED64 -> cursor.skipFixed64()
                else -> throw ParseException("Unsupported wire type $wire in ValueInfoProto")
            }
        }
        return (tensor ?: ParsedTensor("", emptyList(), "unknown")).copy(name = name ?: "")
    }

    private fun parseType(bytes: ByteArray): ParsedTensor {
        val cursor = ProtobufReader(bytes)
        var elemType = -1
        var shape: List<Long?> = emptyList()
        while (!cursor.exhausted()) {
            val (field, wire) = cursor.readTag()
            when {
                field == 9 && wire == ProtobufReader.WIRE_LEN -> {
                    val tensor = parseTensorType(cursor.readLengthDelimited())
                    elemType = tensor.first
                    shape = tensor.second
                }
                wire == ProtobufReader.WIRE_VARINT -> cursor.skipVarint()
                wire == ProtobufReader.WIRE_LEN -> cursor.skipLengthDelimited()
                wire == ProtobufReader.WIRE_FIXED32 -> cursor.skipFixed32()
                wire == ProtobufReader.WIRE_FIXED64 -> cursor.skipFixed64()
                else -> throw ParseException("Unsupported wire type $wire in TypeProto")
            }
        }
        return ParsedTensor("", shape, elemTypeName(elemType))
    }

    private fun parseTensorType(bytes: ByteArray): Pair<Int, List<Long?>> {
        val cursor = ProtobufReader(bytes)
        var elemType = -1
        var dims: List<Long?>? = null
        while (!cursor.exhausted()) {
            val (field, wire) = cursor.readTag()
            when {
                field == 1 && wire == ProtobufReader.WIRE_VARINT -> elemType = cursor.readVarint().toInt()
                field == 2 && wire == ProtobufReader.WIRE_LEN -> dims = parseShape(cursor.readLengthDelimited())
                wire == ProtobufReader.WIRE_VARINT -> cursor.skipVarint()
                wire == ProtobufReader.WIRE_LEN -> cursor.skipLengthDelimited()
                wire == ProtobufReader.WIRE_FIXED32 -> cursor.skipFixed32()
                wire == ProtobufReader.WIRE_FIXED64 -> cursor.skipFixed64()
                else -> throw ParseException("Unsupported wire type $wire in TensorTypeProto")
            }
        }
        return elemType to (dims ?: emptyList())
    }

    private fun parseShape(bytes: ByteArray): List<Long?> {
        val cursor = ProtobufReader(bytes)
        val dims = ArrayList<Long?>()
        while (!cursor.exhausted()) {
            val (field, wire) = cursor.readTag()
            when {
                field == 2 && wire == ProtobufReader.WIRE_LEN -> dims += parseDimension(cursor.readLengthDelimited())
                wire == ProtobufReader.WIRE_VARINT -> cursor.skipVarint()
                wire == ProtobufReader.WIRE_LEN -> cursor.skipLengthDelimited()
                wire == ProtobufReader.WIRE_FIXED32 -> cursor.skipFixed32()
                wire == ProtobufReader.WIRE_FIXED64 -> cursor.skipFixed64()
                else -> throw ParseException("Unsupported wire type $wire in TensorShapeProto")
            }
        }
        return dims
    }

    /** null dimension = symbolic (dynamic); statically unverifiable by design. */
    private fun parseDimension(bytes: ByteArray): Long? {
        val cursor = ProtobufReader(bytes)
        var value: Long? = null
        while (!cursor.exhausted()) {
            val (field, wire) = cursor.readTag()
            when {
                field == 1 && wire == ProtobufReader.WIRE_VARINT -> value = cursor.readVarint()
                field == 2 && wire == ProtobufReader.WIRE_LEN -> value = null.also { cursor.readString() }
                wire == ProtobufReader.WIRE_VARINT -> cursor.skipVarint()
                wire == ProtobufReader.WIRE_LEN -> cursor.skipLengthDelimited()
                wire == ProtobufReader.WIRE_FIXED32 -> cursor.skipFixed32()
                wire == ProtobufReader.WIRE_FIXED64 -> cursor.skipFixed64()
                else -> throw ParseException("Unsupported wire type $wire in Dimension")
            }
        }
        return value
    }

    private fun elemTypeName(code: Int): String = when (code) {
        1 -> "float32"
        2 -> "uint8"
        3 -> "int8"
        4 -> "uint16"
        5 -> "int16"
        6 -> "int32"
        7 -> "int64"
        9 -> "bool"
        10 -> "float16"
        12 -> "float64"
        -1 -> "unknown"
        else -> "type$code"
    }
}

internal class ProtobufReader(private val bytes: ByteArray) {
    private var pos = 0

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LEN = 2
        const val WIRE_FIXED32 = 5
    }

    fun exhausted(): Boolean = pos >= bytes.size

    fun readTag(): Pair<Int, Int> {
        val tag = readVarint()
        return (tag ushr 3).toInt() to (tag and 0x7).toInt()
    }

    fun readVarint(): Long {
        var shift = 0
        var result = 0L
        while (shift < 64) {
            if (pos >= bytes.size) throw OnnxTensorMetadataParser.ParseException("Truncated varint")
            val b = bytes[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw OnnxTensorMetadataParser.ParseException("Varint too long")
    }

    fun readLengthDelimited(): ByteArray {
        val length = readVarint().toInt()
        if (pos + length > bytes.size) throw OnnxTensorMetadataParser.ParseException("Truncated length-delimited field")
        val out = bytes.copyOfRange(pos, pos + length)
        pos += length
        return out
    }

    fun readString(): String = String(readLengthDelimited(), Charsets.UTF_8)

    fun skipVarint() {
        readVarint()
    }

    fun skipLengthDelimited() {
        val length = readVarint().toInt()
        if (pos + length > bytes.size) throw OnnxTensorMetadataParser.ParseException("Truncated length-delimited field")
        pos += length
    }

    fun skipFixed32() {
        pos += 4
    }

    fun skipFixed64() {
        pos += 8
    }
}
