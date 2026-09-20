package de.shakie.iss.observer

import android.graphics.Matrix
import android.graphics.Rect
import android.util.SizeF
import android.view.Surface
import kotlin.math.*

enum class CalibrationAccuracy { CALIBRATED_INTRINSICS, APPROXIMATE_FOCAL_LENGTH, VIRTUAL_SKY }
enum class SensorAccuracyLevel { GEOMAGNETIC_TRUE_NORTH, GAME_ROTATION_RELATIVE, SENSOR_UNAVAILABLE }

data class CameraProjectionData(
    val calibrationAccuracy: CalibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY,
    val focalLengthMm: Float? = null,
    val sensorPhysicalSize: SizeF? = null,
    val activeArraySize: Rect? = null,
    val sensorOrientation: Int = 90,
    val lensFacing: Int = 1,
    val intrinsicCalibration: FloatArray? = null,
    val distortionModes: IntArray? = null,
    val lensDistortion: FloatArray? = null,
    val currentZoomRatio: Float = 1f,
    val sensorToViewTransform: Matrix? = null,
    val viewWidth: Int = 0,
    val viewHeight: Int = 0,
    val displayRotation: Int = Surface.ROTATION_0,
    val virtualHfovDeg: Float = 62f,
    val virtualVfovDeg: Float = 76f,
    // Per-capture, per-physical-lens calibration; already includes effective readout/zoom.
    val lensRayCalibration: LensRayCalibration? = null,
    val isProjectionReady: Boolean = true,
    val calibrationNote: String? = null
)

data class ProjectedPoint(
    val screenX: Float, val screenY: Float,
    val isBehindCamera: Boolean, val isInViewBounds: Boolean,
    val rayCameraX: Float, val rayCameraY: Float, val rayCameraZ: Float,
    val offscreenBearingDeg: Float
)
data class HorizonLineData(val isVisible: Boolean, val startX: Float, val startY: Float,
    val endX: Float, val endY: Float, val rollDeg: Float)

/** Observer projection only. The independent Filament orbit camera is not affected. */
object CameraProjector {
    fun horizontalToWorldEnu(azimuthDeg: Double, elevationDeg: Double): FloatArray {
        val az=Math.toRadians(azimuthDeg);val el=Math.toRadians(elevationDeg)
        return floatArrayOf((cos(el)*sin(az)).toFloat(),(cos(el)*cos(az)).toFloat(),sin(el).toFloat())
    }
    fun worldToDevice(worldVec: FloatArray, rotationMatrix: FloatArray): FloatArray {
        val x=worldVec[0];val y=worldVec[1];val z=worldVec[2]
        return floatArrayOf(rotationMatrix[0]*x+rotationMatrix[4]*y+rotationMatrix[8]*z,
            rotationMatrix[1]*x+rotationMatrix[5]*y+rotationMatrix[9]*z,
            rotationMatrix[2]*x+rotationMatrix[6]*y+rotationMatrix[10]*z)
    }
    fun deviceToViewFrame(deviceVec: FloatArray, displayRotation: Int): FloatArray {
        val x=deviceVec[0];val y=deviceVec[1];val z=-deviceVec[2]
        return when(displayRotation){
            Surface.ROTATION_90 -> floatArrayOf(-y,-x,z)
            Surface.ROTATION_180 -> floatArrayOf(-x,y,z)
            Surface.ROTATION_270 -> floatArrayOf(y,x,z)
            else -> floatArrayOf(x,-y,z)
        }
    }
    private fun viewToDevice(x:Float,y:Float,z:Float,rotation:Int):FloatArray=when(rotation){
        Surface.ROTATION_90 -> floatArrayOf(-y,-x,-z)
        Surface.ROTATION_180 -> floatArrayOf(-x,y,-z)
        Surface.ROTATION_270 -> floatArrayOf(y,x,-z)
        else -> floatArrayOf(x,-y,-z)
    }
    fun computeEffectiveFocalLengths(data: CameraProjectionData, viewWidth: Float, viewHeight: Float): Pair<Float,Float> {
        val zoom=data.currentZoomRatio.coerceAtLeast(.01f)
        val f=data.focalLengthMm;val size=data.sensorPhysicalSize
        if(data.calibrationAccuracy==CalibrationAccuracy.VIRTUAL_SKY || f==null || size==null){
            return Pair(viewWidth*.5f/tan(Math.toRadians(data.virtualHfovDeg/2.0)).toFloat()*zoom,
                viewHeight*.5f/tan(Math.toRadians(data.virtualVfovDeg/2.0)).toFloat()*zoom)
        }
        val rotation=(data.sensorOrientation-data.displayRotation*90+360)%360
        val tx=(if(rotation==90||rotation==270) size.height else size.width)/(2f*f)
        val ty=(if(rotation==90||rotation==270) size.width else size.height)/(2f*f)
        val focal=max(viewWidth/(2f*tx),viewHeight/(2f*ty))*zoom
        return focal to focal
    }
    private fun calibratedPixel(dev:FloatArray,data:CameraProjectionData):FloatArray?{
        if(data.calibrationAccuracy==CalibrationAccuracy.VIRTUAL_SKY) return null
        val model=data.lensRayCalibration ?: return null
        val transform=data.sensorToViewTransform ?: return null
        val p=model.projectDeviceRay(dev[0].toDouble(),dev[1].toDouble(),dev[2].toDouble()) ?: return null
        return floatArrayOf(p[0].toFloat(),p[1].toFloat()).also { transform.mapPoints(it) }.takeIf { it.all(Float::isFinite) }
    }
    fun projectDirection(azimuthDeg:Double,elevationDeg:Double,rotationMatrix:FloatArray,data:CameraProjectionData):ProjectedPoint{
        val w=data.viewWidth.toFloat().coerceAtLeast(1f);val h=data.viewHeight.toFloat().coerceAtLeast(1f)
        val cx=w*.5f;val cy=h*.5f
        val dev=worldToDevice(horizontalToWorldEnu(azimuthDeg,elevationDeg),rotationMatrix)
        val v=deviceToViewFrame(dev,data.displayRotation)
        val behind=v[2]<=.001f
        val x:Float;val y:Float
        if(!behind){
            val calibrated=calibratedPixel(dev,data)
            if(calibrated!=null){x=calibrated[0];y=calibrated[1]}else{
                val p=screenProjection(data,w,h)
                x=p.cx+p.xx*v[0]/v[2]+p.xy*v[1]/v[2]
                y=p.cy+p.yx*v[0]/v[2]+p.yy*v[1]/v[2]
            }
        }else{
            val r=hypot(v[0],v[1])
            if(r>1e-4f){x=cx+v[0]/r*max(w,h);y=cy+v[1]/r*max(w,h)}else{x=cx-max(w,h);y=cy}
        }
        return ProjectedPoint(x,y,behind,!behind&&x in 0f..w&&y in 0f..h,v[0],v[1],v[2],
            Math.toDegrees(atan2((y-cy).toDouble(),(x-cx).toDouble())).toFloat())
    }
    fun projectHorizonLine(rotationMatrix:FloatArray,data:CameraProjectionData):HorizonLineData{
        val w=data.viewWidth.toFloat().coerceAtLeast(1f);val h=data.viewHeight.toFloat().coerceAtLeast(1f)
        val n=deviceToViewFrame(worldToDevice(floatArrayOf(0f,0f,1f),rotationMatrix),data.displayRotation)
        val p=screenProjection(data,w,h)
        val determinant=p.xx*p.yy-p.xy*p.yx
        val roll=Math.toDegrees(atan2(n[0].toDouble(),-n[1].toDouble())).toFloat()
        if(abs(determinant)<1e-6f) return HorizonLineData(false,0f,0f,0f,0f,roll)
        val a=(n[0]*p.yy-n[1]*p.yx)/determinant
        val b=(n[1]*p.xx-n[0]*p.xy)/determinant
        val c=n[2]-a*p.cx-b*p.cy
        val points=mutableListOf<Pair<Float,Float>>()
        fun add(x:Float,y:Float){if(x.isFinite()&&y.isFinite()&&x in -.01f..(w+.01f)&&y in -.01f..(h+.01f)&&
            points.none{abs(it.first-x)<.01f&&abs(it.second-y)<.01f}) points.add(x.coerceIn(0f,w) to y.coerceIn(0f,h))}
        if(abs(b)>1e-8f){add(0f,-c/b);add(w,-(a*w+c)/b)}
        if(abs(a)>1e-8f){add(-c/a,0f);add(-(b*h+c)/a,h)}
        if(points.size<2) return HorizonLineData(false,0f,0f,0f,0f,roll)
        return HorizonLineData(true,points[0].first,points[0].second,points[1].first,points[1].second,roll)
    }
    /** Raw wide-angle output can bend a straight horizon. Sample through the same ray model. */
    fun drawHorizon(canvas:android.graphics.Canvas,rotation:FloatArray,data:CameraProjectionData,paint:android.graphics.Paint){
        if(data.calibrationAccuracy==CalibrationAccuracy.VIRTUAL_SKY || data.lensRayCalibration?.distortion.isNullOrEmpty()){
            val line=projectHorizonLine(rotation,data)
            if(line.isVisible) canvas.drawLine(line.startX,line.startY,line.endX,line.endY,paint)
            return
        }
        var prev:ProjectedPoint?=null
        for(az in 0..360){
            val current=projectDirection(az.toDouble(),0.0,rotation,data)
            val previous=prev
            if(previous!=null&&!previous.isBehindCamera&&!current.isBehindCamera){
                val clipped=de.shakie.iss.orbit.TrailClip.segment(previous.screenX.toDouble(),previous.screenY.toDouble(),
                    current.screenX.toDouble(),current.screenY.toDouble(),data.viewWidth.toDouble(),data.viewHeight.toDouble())
                if(clipped!=null) canvas.drawLine(clipped[0].toFloat(),clipped[1].toFloat(),clipped[2].toFloat(),clipped[3].toFloat(),paint)
            }
            prev=current
        }
    }
    private data class ScreenProjection(val cx:Float,val cy:Float,val xx:Float,val xy:Float,val yx:Float,val yy:Float)
    private fun screenProjection(data:CameraProjectionData,w:Float,h:Float):ScreenProjection{
        val p0=calibratedPixel(viewToDevice(0f,0f,1f,data.displayRotation),data)
        val px=calibratedPixel(viewToDevice(1f,0f,1f,data.displayRotation),data)
        val py=calibratedPixel(viewToDevice(0f,1f,1f,data.displayRotation),data)
        if(p0!=null&&px!=null&&py!=null) return ScreenProjection(p0[0],p0[1],px[0]-p0[0],py[0]-p0[0],px[1]-p0[1],py[1]-p0[1])
        // Existing static calibration path retained for callers/tests without live result metadata.
        val m=data.sensorToViewTransform;val active=data.activeArraySize;val k=data.intrinsicCalibration
        val hasK=k!=null&&k.size>=4&&k.take(4).all{it.isFinite()}&&k[0]>0&&k[1]>0
        val f=data.focalLengthMm;val size=data.sensorPhysicalSize
        val hasFocal=f!=null&&f.isFinite()&&f>0&&size!=null&&size.width>0&&size.height>0
        if(data.calibrationAccuracy!=CalibrationAccuracy.VIRTUAL_SKY&&m!=null&&active!=null&&active.width()>0&&active.height()>0&&(hasK||hasFocal)){
            val fx=if(hasK) k!![0] else f!!*active.width()/size!!.width
            val fy=if(hasK) k!![1] else f!!*active.height()/size!!.height
            val cx=active.left+if(hasK) k!![2] else active.width()/2f
            val cy=active.top+if(hasK) k!![3] else active.height()/2f
            val skew=if(hasK&&k!!.size>=5&&k[4].isFinite()) k[4] else 0f
            val a=Math.toRadians(((data.sensorOrientation-data.displayRotation*90+360)%360).toDouble())
            val co=cos(a).toFloat();val si=sin(a).toFloat()
            fun point(x:Float,y:Float):Pair<Float,Float>{val sx=co*x+si*y;val sy=-si*x+co*y;return cx+fx*sx+skew*sy to cy+fy*sy}
            val xx=point(1f,0f);val yy=point(0f,1f)
            val pts=floatArrayOf(cx,cy,xx.first,xx.second,yy.first,yy.second);m.mapPoints(pts)
            if(pts.all{it.isFinite()}) return ScreenProjection(pts[0],pts[1],pts[2]-pts[0],pts[4]-pts[0],pts[3]-pts[1],pts[5]-pts[1])
        }
        val (fx,fy)=computeEffectiveFocalLengths(data,w,h)
        return ScreenProjection(w*.5f,h*.5f,fx,0f,0f,fy)
    }
    fun createRotationMatrix(azimuthDeg:Float,pitchDeg:Float,rollDeg:Float):FloatArray{
        val az=Math.toRadians(azimuthDeg.toDouble());val el=Math.toRadians(pitchDeg.toDouble());val roll=Math.toRadians(rollDeg.toDouble())
        val lx=(cos(el)*sin(az)).toFloat();val ly=(cos(el)*cos(az)).toFloat();val lz=sin(el).toFloat()
        val zx=-lx;val zy=-ly;val zz=-lz
        val upY=if(abs(lz)>.999f) 1f else 0f;val upZ=if(abs(lz)>.999f) 0f else 1f
        var xx=ly*upZ-lz*upY;var xy=-lx*upZ;var xz=lx*upY
        val len=sqrt(xx*xx+xy*xy+xz*xz).coerceAtLeast(1e-6f);xx/=len;xy/=len;xz/=len
        val yx=zy*xz-zz*xy;val yy=zz*xx-zx*xz;val yz=zx*xy-zy*xx
        val co=cos(roll).toFloat();val si=sin(roll).toFloat()
        return floatArrayOf(co*xx+si*yx,-si*xx+co*yx,zx,0f,
            co*xy+si*yy,-si*xy+co*yy,zy,0f,co*xz+si*yz,-si*xz+co*yz,zz,0f,0f,0f,0f,1f)
    }
}
