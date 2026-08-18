package com.lemon.prayeralarm

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lemon.prayeralarm.databinding.ActivityQiblaBinding
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Compass needle pointing toward the Kaaba from the user's stored coordinates. */
class QiblaActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityQiblaBinding
    private lateinit var prefs: PrefsRepository
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null

    /** Great-circle bearing to the Kaaba, degrees clockwise from true north. */
    private var qiblaBearing = 0.0

    /** Declination between magnetic and true north at this location. */
    private var declination = 0.0f

    private var currentRotation = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQiblaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsRepository(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (!prefs.hasLocation) {
            binding.textQiblaStatus.text = getString(R.string.qibla_no_location)
            return
        }

        qiblaBearing = bearingToKaaba(prefs.latitude, prefs.longitude)
        declination = GeomagneticField(
            prefs.latitude.toFloat(),
            prefs.longitude.toFloat(),
            0f,
            System.currentTimeMillis()
        ).declination

        binding.textQiblaBearing.text =
            getString(R.string.qibla_bearing, qiblaBearing.toInt())

        if (rotationSensor == null) {
            // Without a magnetometer the needle cannot track the device; the bearing above
            // is still correct, so show that rather than an empty dial.
            binding.textQiblaStatus.text = getString(R.string.qibla_no_sensor)
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR || !prefs.hasLocation) return

        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(matrix, orientation)

        // getOrientation reports magnetic north; the qibla bearing is from true north.
        val magneticAzimuth = Math.toDegrees(orientation[0].toDouble())
        val trueAzimuth = magneticAzimuth + declination
        val target = ((qiblaBearing - trueAzimuth) % 360.0).toFloat()

        // Ignore sub-degree jitter so the needle does not shiver while held still.
        if (abs(target - currentRotation) < 1f) return
        currentRotation = target
        binding.imageNeedle.rotation = target
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
        ) {
            binding.textQiblaStatus.text = getString(R.string.qibla_calibrate)
        } else {
            binding.textQiblaStatus.text = ""
        }
    }

    private fun bearingToKaaba(latitude: Double, longitude: Double): Double {
        val phiK = Math.toRadians(KAABA_LAT)
        val lambdaK = Math.toRadians(KAABA_LNG)
        val phi = Math.toRadians(latitude)
        val lambda = Math.toRadians(longitude)

        val y = sin(lambdaK - lambda)
        val x = cos(phi) * tan(phiK) - sin(phi) * cos(lambdaK - lambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    companion object {
        private const val KAABA_LAT = 21.4225
        private const val KAABA_LNG = 39.8262
    }
}
