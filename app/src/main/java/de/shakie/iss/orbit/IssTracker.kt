package de.shakie.iss.orbit

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class IssSnapshot(
    val latitude: Double, val longitude: Double, val altitudeKm: Double, val velocityKmh: Double,
    val sunlightFactor: Float, val isEclipsed: Boolean, val sun: SunPosition,
    val horizontal: HorizontalCoordinates?, val nextPass: IssPass?, val timestampMillis: Long,
    val observerLat: Double = 52.5200, val observerLon: Double = 13.4050,
    val trajectory: OrbitTrajectory? = null,
    val observerPositionKnown: Boolean = horizontal != null,
    val passPredictionPending: Boolean = false,
    val passPredictionError: Boolean = false
)

class IssTracker(private val context: Context) {
    private val client = OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS).build()
    private var currentTle = Tle(25544,26,259.85263506,0.00013566,51.6307,206.4210,0.0004838,147.2470,212.8820,15.49143506,58596)
    @Volatile private var propagator = Sgp4Propagator(currentTle)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private data class ObserverPosition(val lat:Double,val lon:Double,val alt:Double)
    @Volatile private var observer: ObserverPosition? = null
    // Retained for callers; the UI uses the atomic setObserverPosition method.
    var observerLat:Double?
        get()=observer?.lat
        set(value){observer=if(value==null) null else ObserverPosition(value,observer?.lon?:0.0,observer?.alt?:0.05);invalidatePredictions()}
    var observerLon:Double?
        get()=observer?.lon
        set(value){observer=if(value==null) null else ObserverPosition(observer?.lat?:0.0,value,observer?.alt?:0.05);invalidatePredictions()}
    var observerAltKm:Double
        get()=observer?.alt?:0.05
        set(value){observer=observer?.copy(alt=value);invalidatePredictions()}
    fun setObserverPosition(latitude:Double,longitude:Double,altitudeKm:Double){
        if(!latitude.isFinite()||!longitude.isFinite()||!altitudeKm.isFinite()||latitude !in -90.0..90.0||longitude !in -180.0..180.0) return
        val updated=ObserverPosition(latitude,longitude,altitudeKm)
        if(updated!=observer){observer=updated;invalidatePredictions()}
    }

    private data class PassRequest(val model:Sgp4Propagator,val observer:ObserverPosition,val time:Long)
    private data class PassAnswer(val request:PassRequest,val pass:IssPass?,val error:Boolean=false)
    private val passLock=Any()
    @Volatile private var passAnswer:PassAnswer?=null
    @Volatile private var pendingPass:PassRequest?=null
    private var passJob:Job?=null
    private fun invalidatePredictions(){ synchronized(passLock){passAnswer=null;pendingPass=null;passJob?.cancel()};cachedTrajectory=null }
    private fun refreshPass(time:Long){
        val obs=observer?:return
        val model=propagator
        synchronized(passLock){
            val answer=passAnswer
            if(answer!=null&&answer.request.model===model&&answer.request.observer==obs&&
                abs(time-answer.request.time)<60000L&&(answer.pass==null||time<=answer.pass.setTimeMillis)) return
            val pending=pendingPass
            if(passJob?.isActive==true&&pending!=null&&pending.model===model&&pending.observer==obs&&abs(time-pending.time)<60000) return
            passJob?.cancel()
            val request=PassRequest(model,obs,time)
            pendingPass=request
            passJob=scope.launch(Dispatchers.Default){
                try {
                    val pass=PassPredictor.findNextPass(model,obs.lat,obs.lon,obs.alt,time)
                    ensureActive()
                    synchronized(passLock){if(pendingPass===request&&propagator===model&&observer==obs){passAnswer=PassAnswer(request,pass);pendingPass=null}}
                }catch(e:CancellationException){throw e}
                catch(e:Exception){
                    synchronized(passLock){if(pendingPass===request){passAnswer=PassAnswer(request,null,true);pendingPass=null}}
                    Log.w("IssTracker","Pass prediction failed",e)
                }
            }
        }
    }

    var trajectoryEnabled:Boolean=true
    private data class CachedTrajectory(val model:Sgp4Propagator,val data:OrbitTrajectory)
    @Volatile private var cachedTrajectory:CachedTrajectory?=null
    private var trajectoryJob:Job?=null
    private val _snapshotFlow=MutableStateFlow(computeSnapshot())
    val snapshotFlow:StateFlow<IssSnapshot> = _snapshotFlow.asStateFlow()
    init {
        loadCachedTle()
        scope.launch {
            refreshTleFromCelestrak()
            while(isActive){delay(2*3600*1000L);refreshTleFromCelestrak()}
        }
    }

    private fun refreshTrajectory(time:Long){
        if(!trajectoryEnabled) return
        val prefs=context.getSharedPreferences("trajectory_options",Context.MODE_PRIVATE)
        if(!prefs.getBoolean("orbit",true)&&!prefs.getBoolean("observer",true)) return
        // Ground geometry does not require a user location. The auxiliary ENU reference
        // is never exposed as a real observer or used for a visibility prediction.
        val obs=observer ?: ObserverPosition(0.0,0.0,0.0)
        val model=propagator;val cached=cachedTrajectory;val old=cached?.data
        if(cached?.model===model&&old!=null&&old.isUsable(time,obs.lat,obs.lon)&&
            abs(time-old.centerTimeMillis)<5000&&abs(obs.alt-old.observerAltKm)<.001) return
        if(trajectoryJob?.isActive==true) return
        trajectoryJob=scope.launch(Dispatchers.Default){
            val points=TrajectorySampler.sample(time,obs.lat,obs.lon,obs.alt){t->ensureActive();model.propagate(t)}
            if(model===propagator&&(observer ?: ObserverPosition(0.0,0.0,0.0))==obs) cachedTrajectory=CachedTrajectory(model,points)
        }
    }
    fun updateFrame(timeMillis:Long=System.currentTimeMillis()):IssSnapshot{
        refreshPass(timeMillis);refreshTrajectory(timeMillis)
        return computeSnapshot(timeMillis).also{_snapshotFlow.value=it}
    }
    private fun computeSnapshot(timeMillis:Long=System.currentTimeMillis()):IssSnapshot{
        val model=propagator;val state=model.propagate(timeMillis);val sun=SolarCoordinates.calculate(timeMillis)
        val light=EclipseCalculator.getSunlightFactor(state.latitudeDeg,state.longitudeDeg,state.altitudeKm,sun)
        val obs=observer
        val horizontal=obs?.let{TopocentricPosition.calculate(it.lat,it.lon,it.alt,state.latitudeDeg,state.longitudeDeg,state.altitudeKm)}
        val trailObserver=obs ?: ObserverPosition(0.0,0.0,0.0)
        val trail=cachedTrajectory?.takeIf{trajectoryEnabled&&it.model===model&&
            it.data.isUsable(timeMillis,trailObserver.lat,trailObserver.lon)&&abs(it.data.observerAltKm-trailObserver.alt)<.001}?.data
        val answer=passAnswer?.takeIf{it.request.model===model&&it.request.observer==obs&&
            abs(timeMillis-it.request.time)<120000&&(it.pass==null||timeMillis<=it.pass.setTimeMillis)}
        return IssSnapshot(state.latitudeDeg,state.longitudeDeg,state.altitudeKm,state.velocityKmh,light,light<.15f,sun,
            horizontal,answer?.pass,timeMillis,obs?.lat?:0.0,obs?.lon?:0.0,trail,obs!=null,obs!=null&&answer==null,answer?.error==true)
    }
    private suspend fun refreshTleFromCelestrak()=withContext(Dispatchers.IO){
        try{
            val req=Request.Builder().url("https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE").build()
            client.newCall(req).execute().use{response->
                if(response.isSuccessful){
                    val lines=response.body?.string()?.lines()?.map{it.trim()}?.filter{it.isNotEmpty()}?:return@use
                    if(lines.size>=3){
                        val tle=Sgp4Propagator.parseTle(lines[1],lines[2]);currentTle=tle;propagator=Sgp4Propagator(tle)
                        invalidatePredictions();saveCachedTle(lines[1],lines[2]);Log.i("IssTracker","Updated ISS TLE")
                    }
                }
            }
        }catch(e:Exception){if(e is CancellationException) throw e;Log.w("IssTracker","Could not refresh TLE",e)}
    }
    private fun saveCachedTle(l1:String,l2:String){runCatching{File(context.cacheDir,"iss_tle.txt").writeText("$l1\n$l2")}}
    private fun loadCachedTle(){runCatching{
        val f=File(context.cacheDir,"iss_tle.txt")
        if(f.exists()){
            val lines=f.readLines().map{it.trim()}.filter{it.isNotEmpty()}
            if(lines.size>=2){currentTle=Sgp4Propagator.parseTle(lines[0],lines[1]);propagator=Sgp4Propagator(currentTle)}
        }
    }}
    fun destroy(){scope.cancel()}
}
