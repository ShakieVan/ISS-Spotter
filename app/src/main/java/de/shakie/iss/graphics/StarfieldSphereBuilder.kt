package de.shakie.iss.graphics

import android.graphics.*
import com.google.android.filament.*
import de.shakie.iss.astronomy.CelestialCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object StarfieldSphereBuilder {

    /**
     * Builds a 3D billboard starfield mesh containing real landmark stars from CelestialCatalog
     * plus thousands of realistic background stars. Each star is a quad facing the origin,
     * rendered as a mathematically perfect glowing Gaussian circle with zero polar distortion.
     */
    fun buildStarBillboards(
        engine: Engine,
        radius: Float = 70.0f
    ): EarthMesh {
        val stars = mutableListOf<StarVertexData>()

        // 1. Landmark stars from CelestialCatalog
        for (star in CelestialCatalog.stars) {
            val raRad = Math.toRadians(star.raHours * 15.0).toFloat()
            val decRad = Math.toRadians(star.decDeg).toFloat()

            val nx = cos(decRad) * cos(raRad)
            val ny = sin(decRad)
            val nz = cos(decRad) * sin(raRad)

            val magClamped = star.magnitude.coerceIn(-1.5, 3.5).toFloat()
            val size = (0.52f - magClamped * 0.07f).coerceIn(0.30f, 0.72f)

            val (r, g, b) = when (star.name) {
                "Beteigeuze", "Aldebaran", "Antares" -> Triple(1.0f, 0.65f, 0.40f)
                "Rigel", "Wega", "Sirius" -> Triple(0.85f, 0.94f, 1.0f)
                "Capella", "Arktur" -> Triple(1.0f, 0.90f, 0.60f)
                else -> Triple(0.95f, 0.97f, 1.0f)
            }
            val alpha = (2.5f - magClamped * 0.35f).coerceIn(1.4f, 3.0f)
            stars.add(StarVertexData(nx, ny, nz, size, r, g, b, alpha))
        }

        // 2. Realistic background stars (dense along the celestial sphere)
        val random = java.util.Random(4242L)
        for (i in 0 until 3200) {
            // Uniform point on sphere via Marsaglia method
            var x: Float
            var y: Float
            var s: Float
            do {
                x = random.nextFloat() * 2.0f - 1.0f
                y = random.nextFloat() * 2.0f - 1.0f
                s = x * x + y * y
            } while (s >= 1.0f || s < 0.0001f)

            val sqrtOneMinusS = sqrt(1.0f - s)
            val nx = 2.0f * x * sqrtOneMinusS
            val ny = 2.0f * y * sqrtOneMinusS
            val nz = 1.0f - 2.0f * s

            val size = 0.12f + random.nextFloat() * 0.20f
            val tint = random.nextFloat()
            val (r, g, b) = when {
                tint > 0.80f -> Triple(0.80f, 0.90f, 1.0f)
                tint < 0.15f -> Triple(1.0f, 0.90f, 0.70f)
                else -> Triple(1.0f, 1.0f, 1.0f)
            }
            val alpha = 0.80f + random.nextFloat() * 1.10f
            stars.add(StarVertexData(nx, ny, nz, size, r, g, b, alpha))
        }

        val numStars = stars.size
        val numVertices = numStars * 4
        val numIndices = numStars * 6

        val posBuffer = ByteBuffer.allocateDirect(numVertices * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val uvBuffer = ByteBuffer.allocateDirect(numVertices * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val colorBuffer = ByteBuffer.allocateDirect(numVertices * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val indexBufferData = ByteBuffer.allocateDirect(numIndices * 4).order(ByteOrder.nativeOrder()).asIntBuffer()

        for ((idx, s) in stars.withIndex()) {
            val px = radius * s.nx
            val py = radius * s.ny
            val pz = radius * s.nz

            // Compute two orthonormal tangent vectors perpendicular to normal
            val (t1x, t1y, t1z) = if (abs(s.ny) < 0.95f) {
                // cross(n, (0, 1, 0))
                val len = sqrt(s.nz * s.nz + s.nx * s.nx).coerceAtLeast(0.001f)
                Triple(s.nz / len, 0f, -s.nx / len)
            } else {
                // cross(n, (1, 0, 0))
                val len = sqrt(s.ny * s.ny + s.nz * s.nz).coerceAtLeast(0.001f)
                Triple(0f, -s.nz / len, s.ny / len)
            }

            // cross(n, t1)
            val t2x = s.ny * t1z - s.nz * t1y
            val t2y = s.nz * t1x - s.nx * t1z
            val t2z = s.nx * t1y - s.ny * t1x

            val sz = s.size

            // 4 quad corners
            val v0x = px - sz * t1x - sz * t2x
            val v0y = py - sz * t1y - sz * t2y
            val v0z = pz - sz * t1z - sz * t2z

            val v1x = px + sz * t1x - sz * t2x
            val v1y = py + sz * t1y - sz * t2y
            val v1z = pz + sz * t1z - sz * t2z

            val v2x = px + sz * t1x + sz * t2x
            val v2y = py + sz * t1y + sz * t2y
            val v2z = pz + sz * t1z + sz * t2z

            val v3x = px - sz * t1x + sz * t2x
            val v3y = py - sz * t1y + sz * t2y
            val v3z = pz - sz * t1z + sz * t2z

            // Put positions
            posBuffer.put(v0x).put(v0y).put(v0z)
            posBuffer.put(v1x).put(v1y).put(v1z)
            posBuffer.put(v2x).put(v2y).put(v2z)
            posBuffer.put(v3x).put(v3y).put(v3z)

            // Put UVs
            uvBuffer.put(0f).put(0f)
            uvBuffer.put(1f).put(0f)
            uvBuffer.put(1f).put(1f)
            uvBuffer.put(0f).put(1f)

            // Put Colors
            for (k in 0 until 4) {
                colorBuffer.put(s.r).put(s.g).put(s.b).put(s.alpha)
            }

            // Put Indices
            val base = idx * 4
            indexBufferData.put(base).put(base + 1).put(base + 2)
            indexBufferData.put(base).put(base + 2).put(base + 3)
        }

        posBuffer.flip()
        uvBuffer.flip()
        colorBuffer.flip()
        indexBufferData.flip()

        val vertexBuffer = VertexBuffer.Builder()
            .vertexCount(numVertices)
            .bufferCount(3)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 2, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)

        vertexBuffer.setBufferAt(engine, 0, posBuffer)
        vertexBuffer.setBufferAt(engine, 1, uvBuffer)
        vertexBuffer.setBufferAt(engine, 2, colorBuffer)

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(numIndices)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)

        indexBuffer.setBuffer(engine, indexBufferData)

        val entity = EntityManager.get().create()
        return EarthMesh(entity, vertexBuffer, indexBuffer)
    }

    /**
     * Builds 3D constellation lines connecting the stars in true space coordinates.
     */
    fun buildConstellationLines(
        engine: Engine,
        radius: Float = 69.8f
    ): EarthMesh {
        val starCoords = mutableMapOf<String, FloatArray>()
        for (star in CelestialCatalog.stars) {
            val raRad = Math.toRadians(star.raHours * 15.0).toFloat()
            val decRad = Math.toRadians(star.decDeg).toFloat()
            val nx = cos(decRad) * cos(raRad)
            val ny = sin(decRad)
            val nz = cos(decRad) * sin(raRad)
            starCoords[star.name] = floatArrayOf(radius * nx, radius * ny, radius * nz)
        }

        val lineSegments = mutableListOf<Pair<FloatArray, FloatArray>>()
        for (c in CelestialCatalog.constellations) {
            for ((s1, s2) in c.lines) {
                val p1 = starCoords[s1]
                val p2 = starCoords[s2]
                if (p1 != null && p2 != null) {
                    lineSegments.add(Pair(p1, p2))
                }
            }
        }

        val numLines = lineSegments.size
        val numVertices = numLines * 2
        val numIndices = numLines * 2

        val posBuffer = ByteBuffer.allocateDirect(numVertices * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val colorBuffer = ByteBuffer.allocateDirect(numVertices * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val indexBufferData = ByteBuffer.allocateDirect(numIndices * 4).order(ByteOrder.nativeOrder()).asIntBuffer()

        for ((idx, line) in lineSegments.withIndex()) {
            val p1 = line.first
            val p2 = line.second

            posBuffer.put(p1[0]).put(p1[1]).put(p1[2])
            posBuffer.put(p2[0]).put(p2[1]).put(p2[2])

            // Luminous celestial cyan/blue
            colorBuffer.put(0.55f).put(0.82f).put(1.0f).put(0.85f)
            colorBuffer.put(0.55f).put(0.82f).put(1.0f).put(0.85f)

            val base = idx * 2
            indexBufferData.put(base).put(base + 1)
        }

        posBuffer.flip()
        colorBuffer.flip()
        indexBufferData.flip()

        val vertexBuffer = VertexBuffer.Builder()
            .vertexCount(numVertices)
            .bufferCount(2)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)

        vertexBuffer.setBufferAt(engine, 0, posBuffer)
        vertexBuffer.setBufferAt(engine, 1, colorBuffer)

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(numIndices)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)

        indexBuffer.setBuffer(engine, indexBufferData)

        val entity = EntityManager.get().create()
        return EarthMesh(entity, vertexBuffer, indexBuffer)
    }

    private data class StarVertexData(
        val nx: Float,
        val ny: Float,
        val nz: Float,
        val size: Float,
        val r: Float,
        val g: Float,
        val b: Float,
        val alpha: Float
    )
}
