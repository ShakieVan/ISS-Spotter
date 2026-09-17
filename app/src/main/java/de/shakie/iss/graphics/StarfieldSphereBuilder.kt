package de.shakie.iss.graphics

import com.google.android.filament.*
import de.shakie.iss.astronomy.CelestialCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object StarfieldSphereBuilder {

    /**
     * Builds a 3D billboard starfield mesh containing real landmark stars from CelestialCatalog
     * with glowing halos, rendering as mathematically perfect radiant Gaussian stars.
     */
    fun buildStarBillboards(
        engine: Engine,
        radius: Float = 68.0f
    ): EarthMesh {
        val stars = mutableListOf<StarVertexData>()

        for (star in CelestialCatalog.stars) {
            val raRad = Math.toRadians(star.raHours * 15.0).toFloat()
            val decRad = Math.toRadians(star.decDeg).toFloat()

            val nx = cos(decRad) * cos(raRad)
            val ny = sin(decRad)
            val nz = cos(decRad) * sin(raRad)

            val mag = star.magnitude.toFloat()
            val baseSize = when {
                mag < 0.5f -> 0.42f
                mag < 1.8f -> 0.32f
                mag < 2.5f -> 0.26f
                else -> 0.20f
            }

            val (r, g, b) = when (star.name) {
                "Beteigeuze", "Aldebaran", "Antares" -> Triple(1.0f, 0.65f, 0.35f)
                "Rigel", "Wega", "Sirius", "Deneb", "Atair" -> Triple(0.80f, 0.92f, 1.0f)
                "Capella", "Arktur", "Polaris" -> Triple(1.0f, 0.92f, 0.65f)
                else -> Triple(0.95f, 0.98f, 1.0f)
            }

            // Outer glowing halo quad
            stars.add(StarVertexData(nx, ny, nz, baseSize * 2.2f, r, g, b, 0.35f))
            // Intense core star quad
            stars.add(StarVertexData(nx, ny, nz, baseSize, r, g, b, 1.0f))
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

            val (t1x, t1y, t1z) = if (abs(s.ny) < 0.95f) {
                val len = sqrt(s.nz * s.nz + s.nx * s.nx).coerceAtLeast(0.001f)
                Triple(s.nz / len, 0f, -s.nx / len)
            } else {
                val len = sqrt(s.ny * s.ny + s.nz * s.nz).coerceAtLeast(0.001f)
                Triple(0f, -s.nz / len, s.ny / len)
            }

            val t2x = s.ny * t1z - s.nz * t1y
            val t2y = s.nz * t1x - s.nx * t1z
            val t2z = s.nx * t1y - s.ny * t1x

            val sz = s.size

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

            posBuffer.put(v0x).put(v0y).put(v0z)
            posBuffer.put(v1x).put(v1y).put(v1z)
            posBuffer.put(v2x).put(v2y).put(v2z)
            posBuffer.put(v3x).put(v3y).put(v3z)

            uvBuffer.put(0f).put(0f)
            uvBuffer.put(1f).put(0f)
            uvBuffer.put(1f).put(1f)
            uvBuffer.put(0f).put(1f)

            for (k in 0 until 4) {
                colorBuffer.put(s.r).put(s.g).put(s.b).put(s.alpha)
            }

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
     * Builds 3D constellation lines as glowing ribbon strips (TRIANGLES)
     * connecting the stars in true space coordinates with ~4px line width.
     */
    fun buildConstellationLines(
        engine: Engine,
        radius: Float = 67.8f,
        ribbonHalfWidth: Float = 0.09f
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
        val numVertices = numLines * 4
        val numIndices = numLines * 6

        val posBuffer = ByteBuffer.allocateDirect(numVertices * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val colorBuffer = ByteBuffer.allocateDirect(numVertices * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val indexBufferData = ByteBuffer.allocateDirect(numIndices * 4).order(ByteOrder.nativeOrder()).asIntBuffer()

        for ((idx, line) in lineSegments.withIndex()) {
            val p1 = line.first
            val p2 = line.second

            // Normal pointing out from origin to center of segment
            val midX = (p1[0] + p2[0]) * 0.5f
            val midY = (p1[1] + p2[1]) * 0.5f
            val midZ = (p1[2] + p2[2]) * 0.5f
            val midLen = sqrt(midX * midX + midY * midY + midZ * midZ).coerceAtLeast(0.001f)
            val nx = midX / midLen
            val ny = midY / midLen
            val nz = midZ / midLen

            // Direction from p1 to p2
            val dx = p2[0] - p1[0]
            val dy = p2[1] - p1[1]
            val dz = p2[2] - p1[2]
            val dLen = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.001f)
            val dirX = dx / dLen
            val dirY = dy / dLen
            val dirZ = dz / dLen

            // Ribbon width perpendicular vector = cross(N, Dir)
            var wx = ny * dirZ - nz * dirY
            var wy = nz * dirX - nx * dirZ
            var wz = nx * dirY - ny * dirX
            val wLen = sqrt(wx * wx + wy * wy + wz * wz).coerceAtLeast(0.001f)
            wx = (wx / wLen) * ribbonHalfWidth
            wy = (wy / wLen) * ribbonHalfWidth
            wz = (wz / wLen) * ribbonHalfWidth

            // 4 vertices of the ribbon quad
            val v0x = p1[0] - wx
            val v0y = p1[1] - wy
            val v0z = p1[2] - wz

            val v1x = p1[0] + wx
            val v1y = p1[1] + wy
            val v1z = p1[2] + wz

            val v2x = p2[0] + wx
            val v2y = p2[1] + wy
            val v2z = p2[2] + wz

            val v3x = p2[0] - wx
            val v3y = p2[1] - wy
            val v3z = p2[2] - wz

            posBuffer.put(v0x).put(v0y).put(v0z)
            posBuffer.put(v1x).put(v1y).put(v1z)
            posBuffer.put(v2x).put(v2y).put(v2z)
            posBuffer.put(v3x).put(v3y).put(v3z)

            // Luminous celestial cyan/blue
            for (k in 0 until 4) {
                colorBuffer.put(0.45f).put(0.85f).put(1.0f).put(0.88f)
            }

            val base = idx * 4
            indexBufferData.put(base).put(base + 1).put(base + 2)
            indexBufferData.put(base).put(base + 2).put(base + 3)
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

    /**
     * Builds 3D constellation labels mesh for all 10 major constellations
     * mapping into the 10-row texture atlas.
     */
    fun buildConstellationLabels(
        engine: Engine,
        radius: Float = 67.5f,
        halfWidth: Float = 2.8f,
        halfHeight: Float = 1.12f
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

        val constellations = CelestialCatalog.constellations
        val cols = 2
        val rows = 5
        val numLabels = constellations.size
        val numVertices = numLabels * 4
        val numIndices = numLabels * 6

        val posBuffer = ByteBuffer.allocateDirect(numVertices * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val uvBuffer = ByteBuffer.allocateDirect(numVertices * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val indexBufferData = ByteBuffer.allocateDirect(numIndices * 4).order(ByteOrder.nativeOrder()).asIntBuffer()

        for (i in 0 until numLabels) {
            val c = constellations[i]

            // Calculate centroid of all stars in this constellation
            val uniqueStars = mutableSetOf<String>()
            for ((s1, s2) in c.lines) {
                uniqueStars.add(s1)
                uniqueStars.add(s2)
            }
            if (uniqueStars.isEmpty()) uniqueStars.add(c.labelStar)

            var avgX = 0f
            var avgY = 0f
            var avgZ = 0f
            for (sName in uniqueStars) {
                val pt = starCoords[sName] ?: continue
                avgX += pt[0]
                avgY += pt[1]
                avgZ += pt[2]
            }
            val count = uniqueStars.size.coerceAtLeast(1)
            avgX /= count
            avgY /= count
            avgZ /= count

            val centerLen = sqrt(avgX * avgX + avgY * avgY + avgZ * avgZ).coerceAtLeast(0.001f)
            val nx = avgX / centerLen
            val ny = avgY / centerLen
            val nz = avgZ / centerLen

            val cx = nx * radius
            val cy = ny * radius
            val cz = nz * radius

            // Tangent basis oriented towards observer at (0,0,0)
            // Center direction vector N = (nx, ny, nz) points from (0,0,0) to billboard center
            // Viewer at (0,0,0) looks towards +N.
            // Reference Up is celestial north (0, 1, 0)
            var upX: Float
            var upY: Float
            var upZ: Float
            if (abs(ny) < 0.95f) {
                // Project (0, 1, 0) onto plane perpendicular to N: Up = (0, 1, 0) - ny * N
                val px = -nx * ny
                val py = 1.0f - ny * ny
                val pz = -nz * ny
                val len = sqrt(px * px + py * py + pz * pz).coerceAtLeast(0.001f)
                upX = px / len
                upY = py / len
                upZ = pz / len
            } else {
                // Near celestial poles, use (0, 0, 1) as reference
                val px = -nx * nz
                val py = -ny * nz
                val pz = 1.0f - nz * nz
                val len = sqrt(px * px + py * py + pz * pz).coerceAtLeast(0.001f)
                upX = px / len
                upY = py / len
                upZ = pz / len
            }

            // Viewer's Right vector = cross(N, Up)
            // When viewer at (0,0,0) looks along +N with Up pointing up, cross(N, Up) points to viewer's right!
            var rX = ny * upZ - nz * upY
            var rY = nz * upX - nx * upZ
            var rZ = nx * upY - ny * upX
            val rLen = sqrt(rX * rX + rY * rY + rZ * rZ).coerceAtLeast(0.001f)
            rX /= rLen
            rY /= rLen
            rZ /= rLen

            // Re-orthogonalize Up = cross(Right, N)
            upX = rY * nz - rZ * ny
            upY = rZ * nx - rX * nz
            upZ = rX * ny - rY * nx

            // 4 corners of the billboard facing the viewer at origin:
            // v0: Bottom-Left (from viewer's viewpoint)
            val v0x = cx - halfWidth * rX - halfHeight * upX
            val v0y = cy - halfWidth * rY - halfHeight * upY
            val v0z = cz - halfWidth * rZ - halfHeight * upZ

            // v1: Bottom-Right (from viewer's viewpoint)
            val v1x = cx + halfWidth * rX - halfHeight * upX
            val v1y = cy + halfWidth * rY - halfHeight * upY
            val v1z = cz + halfWidth * rZ - halfHeight * upZ

            // v2: Top-Right (from viewer's viewpoint)
            val v2x = cx + halfWidth * rX + halfHeight * upX
            val v2y = cy + halfWidth * rY + halfHeight * upY
            val v2z = cz + halfWidth * rZ + halfHeight * upZ

            // v3: Top-Left (from viewer's viewpoint)
            val v3x = cx - halfWidth * rX + halfHeight * upX
            val v3y = cy - halfWidth * rY + halfHeight * upY
            val v3z = cz - halfWidth * rZ + halfHeight * upZ

            posBuffer.put(v0x).put(v0y).put(v0z)
            posBuffer.put(v1x).put(v1y).put(v1z)
            posBuffer.put(v2x).put(v2y).put(v2z)
            posBuffer.put(v3x).put(v3y).put(v3z)

            // Texture Atlas coordinates (2 columns, 5 rows)
            // Row 0 is at the top of the image (V from 0.8 to 1.0)
            // Row 4 is at the bottom of the image (V from 0.0 to 0.2)
            val colIdx = i % cols
            val rowIdx = i / cols

            val uMin = colIdx.toFloat() / cols.toFloat()
            val uMax = (colIdx + 1).toFloat() / cols.toFloat()
            val vTop = 1.0f - rowIdx.toFloat() / rows.toFloat()
            val vBottom = 1.0f - (rowIdx + 1).toFloat() / rows.toFloat()

            // Map UVs to corners:
            // v0: Bottom-Left  -> (uMin, vBottom)
            // v1: Bottom-Right -> (uMax, vBottom)
            // v2: Top-Right    -> (uMax, vTop)
            // v3: Top-Left     -> (uMin, vTop)
            uvBuffer.put(uMin).put(vBottom)
            uvBuffer.put(uMax).put(vBottom)
            uvBuffer.put(uMax).put(vTop)
            uvBuffer.put(uMin).put(vTop)

            // Triangles with CCW winding when viewed from origin:
            val base = i * 4
            indexBufferData.put(base).put(base + 1).put(base + 2)
            indexBufferData.put(base).put(base + 2).put(base + 3)
        }

        posBuffer.flip()
        uvBuffer.flip()
        indexBufferData.flip()

        val vertexBuffer = VertexBuffer.Builder()
            .vertexCount(numVertices)
            .bufferCount(2)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)

        vertexBuffer.setBufferAt(engine, 0, posBuffer)
        vertexBuffer.setBufferAt(engine, 1, uvBuffer)

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
