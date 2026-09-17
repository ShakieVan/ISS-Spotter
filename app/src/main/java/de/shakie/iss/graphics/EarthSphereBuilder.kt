package de.shakie.iss.graphics

import com.google.android.filament.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class EarthMesh(
    val entity: Int,
    val vertexBuffer: VertexBuffer,
    val indexBuffer: IndexBuffer
) {
    fun destroy(engine: Engine) {
        engine.destroyEntity(entity)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        EntityManager.get().destroy(entity)
    }
}

object EarthSphereBuilder {

    /**
     * Builds a Filament sphere mesh representing the Earth with position,
     * tangent quaternion, and equirectangular UV mapping.
     */
    fun build(
        engine: Engine,
        radius: Float = 10.0f,
        latSegments: Int = 48,
        lonSegments: Int = 96
    ): EarthMesh {
        val numVertices = (latSegments + 1) * (lonSegments + 1)
        val numIndices = latSegments * lonSegments * 6

        val posBuffer = ByteBuffer.allocateDirect(numVertices * 3 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val tangentBuffer = ByteBuffer.allocateDirect(numVertices * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val uvBuffer = ByteBuffer.allocateDirect(numVertices * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val indexBufferData = ByteBuffer.allocateDirect(numIndices * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer()

        for (i in 0..latSegments) {
            val theta = (i.toFloat() / latSegments) * Math.PI.toFloat() // 0 (North Pole) to PI (South Pole)
            val sinTheta = sin(theta)
            val cosTheta = cos(theta)

            for (j in 0..lonSegments) {
                // UVs: u from 0 to 1 (wrapping longitude), v from 0 (North Pole) to 1 (South Pole)
                val u = j.toFloat() / lonSegments
                val v = 1.0f - (i.toFloat() / latSegments)

                // In equirectangular maps: u=0 is -180 deg, u=0.5 is Prime Meridian (0 deg), u=1 is +180 deg
                val phi = (u - 0.5f) * 2.0f * Math.PI.toFloat()

                // Coordinates in Filament world: Y is up (North Pole), X is Prime Meridian (phi=0), Z is East (phi=+PI/2)
                val nx = sinTheta * cos(phi)
                val ny = cosTheta
                val nz = sinTheta * sin(phi)

                val x = radius * nx
                val y = radius * ny
                val z = radius * nz

                posBuffer.put(x)
                posBuffer.put(y)
                posBuffer.put(z)

                uvBuffer.put(u)
                uvBuffer.put(v)

                // Quaternion encoding for normal (nx, ny, nz)
                if (nz < -0.999999f) {
                    tangentBuffer.put(1.0f).put(0.0f).put(0.0f).put(0.0f)
                } else {
                    val len = sqrt(nx * nx + ny * ny + (1.0f + nz) * (1.0f + nz))
                    tangentBuffer.put(-ny / len)
                        .put(nx / len)
                        .put(0.0f)
                        .put((1.0f + nz) / len)
                }
            }
        }

        // Indices
        for (i in 0 until latSegments) {
            for (j in 0 until lonSegments) {
                val first = i * (lonSegments + 1) + j
                val second = first + lonSegments + 1

                // First triangle
                indexBufferData.put(first)
                indexBufferData.put(second)
                indexBufferData.put(first + 1)

                // Second triangle
                indexBufferData.put(second)
                indexBufferData.put(second + 1)
                indexBufferData.put(first + 1)
            }
        }

        posBuffer.flip()
        tangentBuffer.flip()
        uvBuffer.flip()
        indexBufferData.flip()

        val vertexBuffer = VertexBuffer.Builder()
            .vertexCount(numVertices)
            .bufferCount(3)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .attribute(VertexBuffer.VertexAttribute.UV0, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)

        vertexBuffer.setBufferAt(engine, 0, posBuffer)
        vertexBuffer.setBufferAt(engine, 1, tangentBuffer)
        vertexBuffer.setBufferAt(engine, 2, uvBuffer)

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(numIndices)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)

        indexBuffer.setBuffer(engine, indexBufferData)

        val entity = EntityManager.get().create()

        return EarthMesh(entity, vertexBuffer, indexBuffer)
    }
}
