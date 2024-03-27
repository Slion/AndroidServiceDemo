package com.landomen.sample.foregroundservice14.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.landomen.sample.foregroundservice14.notification.NotificationsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Simple foreground service that shows a notification to the user and provides location updates.
 */
class ExampleLocationForegroundService : Service() {
    private val binder = LocalBinder()

    private val coroutineScope = CoroutineScope(Job())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var timerJob: Job? = null

    private val _locationFlow = MutableStateFlow<Location?>(null)
    var locationFlow: StateFlow<Location?> = _locationFlow

    inner class LocalBinder : Binder() {
        fun getService(): ExampleLocationForegroundService = this@ExampleLocationForegroundService
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        startAsForegroundService()
        startLocationUpdates()

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        Toast.makeText(this, "Foreground Service created", Toast.LENGTH_SHORT).show()

        setupLocationUpdates()
        startServiceRunningTicker()
        setupColorFilter(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        fusedLocationClient.removeLocationUpdates(locationCallback)
        timerJob?.cancel()
        coroutineScope.coroutineContext.cancelChildren()

        Toast.makeText(this, "Foreground Service destroyed", Toast.LENGTH_SHORT).show()
        setupColorFilter(false)
    }

    /**
     * Promotes the service to a foreground service, showing a notification to the user.
     *
     * This needs to be called within 10 seconds of starting the service or the system will throw an exception.
     */
    private fun startAsForegroundService() {
        // create the notification channel
        NotificationsHelper.createNotificationChannel(this)

        // promote service to foreground service
        ServiceCompat.startForeground(
            this,
            1,
            NotificationsHelper.buildNotification(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        )
    }

    /**
     * Stops the foreground service and removes the notification.
     * Can be called from inside or outside the service.
     */
    fun stopForegroundService() {
        stopSelf()
    }

    /**
     * Sets up the location updates using the FusedLocationProviderClient, but doesn't actually start them.
     * To start the location updates, call [startLocationUpdates].
     */
    private fun setupLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    _locationFlow.value = location
                }
            }
        }
    }

    /**
     * Starts the location updates using the FusedLocationProviderClient.
     */
    private fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(
            LocationRequest.Builder(
                LOCATION_UPDATES_INTERVAL_MS
            ).build(), locationCallback, Looper.getMainLooper()
        )
    }

    /**
     * Starts a ticker that shows a toast every [TICKER_PERIOD_SECONDS] seconds to indicate that the service is still running.
     */
    private fun startServiceRunningTicker() {
        timerJob?.cancel()
        timerJob = coroutineScope.launch {
            tickerFlow()
                .collectLatest {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ExampleLocationForegroundService,
                            "Foreground Service still running!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
        }
    }

    private fun tickerFlow(
        period: Duration = TICKER_PERIOD_SECONDS,
        initialDelay: Duration = TICKER_PERIOD_SECONDS
    ) = flow {
        delay(initialDelay)
        while (true) {
            emit(Unit)
            delay(period)
        }
    }

    var mLayout: FrameLayout? = null

    // Setting up the overlay to draw on top of status bar and navigation bar can be tricky
    // See: https://stackoverflow.com/questions/21380167/draw-bitmaps-on-top-of-the-navigation-bar-in-android
    // See: https://stackoverflow.com/questions/31516089/draw-over-navigation-bar-and-other-apps-on-android-version-5
    // See: https://stackoverflow.com/questions/50677833/full-screen-type-accessibility-overlay
    //
    private fun setupColorFilter(on: Boolean) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (on && mLayout == null) {
            //mLayout = new FxOverlay(this);
            mLayout = FrameLayout(this)

            // Fetch screen size to work out our overlay size
            val display = wm.defaultDisplay
            val size = Point()
            display.getSize(size)
            // We need it to be large enough to cover navigation bar both in portrait and landscape
            // Doing Math.max here didn't work for whatever reason
            val width = size.x + 500
            val height = size.y + 500
            val lp = WindowManager.LayoutParams()
            // We need to explicitly specify our extent so as to make sure we cover the navigation bar
            lp.width = max(width.toDouble(), height.toDouble()).toInt()
            lp.height = max(width.toDouble(), height.toDouble()).toInt()
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            lp.format = PixelFormat.TRANSLUCENT
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

            // We don't want to capture input
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE


            //lp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_FULLSCREEN
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            lp.gravity = Gravity.TOP
            wm.addView(mLayout, lp)
        }
        else if (!on && mLayout != null) {
            // Disable our overlay
            wm.removeView(mLayout)
            mLayout = null
            //mColorLayout = null;
        }

            //val blackAlpha = ColorUtils.setAlphaComponent(0, 0xFF - 0x88)
            //mLayout?.setBackgroundColor(ColorUtils.compositeColors(blackAlpha, 0xFFFF0000))
            mLayout?.setBackgroundColor(Color.argb(0x88, 0xFF, 0, 0))

    }


    companion object {
        private const val TAG = "ExampleForegroundService"
        private val LOCATION_UPDATES_INTERVAL_MS = 1.seconds.inWholeMilliseconds
        private val TICKER_PERIOD_SECONDS = 5.seconds
    }
}
