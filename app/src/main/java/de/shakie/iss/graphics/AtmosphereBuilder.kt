package de.shakie.iss.graphics

import com.google.android.filament.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object AtmosphereBuilder {

    /**
     * Builds an atmospheric bounding sphere shell mesh at radius 25.0f.
     * The camera in LEO (R ~ 10.6f) is safely inside this bounding volume,
     * completely eliminating near-plane clipping and polygon tessellation artifacts.
     */
    fun buildAtmosphereShell(
        engine: Engine,
        radius: Float = 25.0f,
        latSegments: Int = 48,
        lonSegments: Int = 96
    ): EarthMesh {
        val numVertices = (latSegments + 1) * (lonSegments + 1)
        val numIndices = latSegments * lonSegments * 6

        val posBuffer = ByteBuffer.allocateDirect(numVertices * 3 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val uvBuffer = ByteBuffer.allocateDirect(numVertices * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val indexBufferData = ByteBuffer.allocateDirect(numIndices * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer()

        for (i in 0..latSegments) {
            val theta = (i.toFloat() / latSegments) * Math.PI.toFloat()
            val sinTheta = sin(theta)
            val cosTheta = cos(theta)

            for (j in 0..lonSegments) {
                val u = j.toFloat() / lonSegments
                val v = 1.0f - (i.toFloat() / latSegments)
                val phi = (u - 0.5f) * 2.0f * Math.PI.toFloat()

                val nx = sinTheta * cos(phi)
                val ny = cosTheta
                val nz = sinTheta * sin(phi)

                posBuffer.put(radius * nx)
                posBuffer.put(radius * ny)
                posBuffer.put(radius * nz)

                uvBuffer.put(u)
                uvBuffer.put(v)
            }
        }

        // Inside-facing triangles (camera is inside the bounding sphere)
        for (i in 0 until latSegments) {
            for (j in 0 until lonSegments) {
                val first = i * (lonSegments + 1) + j
                val second = first + lonSegments + 1

                // Clockwise from outside = CCW viewed from inside
                indexBufferData.put(first)
                indexBufferData.put(second)
                indexBufferData.put(first + 1)

                indexBufferData.put(second)
                indexBufferData.put(second + 1)
                indexBufferData.put(first + 1)
            }
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
}
