package me.sithiramunasinghe.flutter.flutter_radio_player

import android.app.Activity
import android.content.*
import android.os.IBinder
import androidx.annotation.NonNull
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.EventChannel.StreamHandler
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import me.sithiramunasinghe.flutter.flutter_radio_player.core.PlayerItem
import me.sithiramunasinghe.flutter.flutter_radio_player.core.StreamingCore
import me.sithiramunasinghe.flutter.flutter_radio_player.core.enums.PlayerMethods
import java.util.*
import java.util.logging.Logger
import kotlin.concurrent.schedule


/** FlutterRadioPlayerPlugin */
class FlutterRadioPlayerPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var activityJavaClass: Class<Activity>
    private var logger = Logger.getLogger(FlutterRadioPlayerPlugin::javaClass.name)

    private lateinit var methodChannel: MethodChannel

    private var mEventPlaybackStatusSink: EventSink? = null
    private var mEventMetaDataSink: EventSink? = null
    private var mEventVolumeSink: EventSink? = null

    private var isBound = false
    private lateinit var applicationContext: Context
    private var coreService: StreamingCore? = null

    private val intentFilter = IntentFilter().apply {
        addAction(BROADCAST_ACTION_PLAYBACK_STATUS)
        addAction(BROADCAST_ACTION_META_DATA)
        addAction(BROADCAST_ACTION_VOLUME)
    }


    companion object {

        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val instance = FlutterRadioPlayerPlugin()
            instance.buildEngine(registrar.activeContext()!!, registrar.messenger()!!)
        }

        /**
         * Broadcasts name
         * */
        const val BROADCAST_ACTION_PLAYBACK_STATUS = "playback_status"
        const val BROADCAST_ACTION_META_DATA = "changed_meta_data"
        const val BROADCAST_ACTION_VOLUME = "volume_changed"


        const val methodChannelName = "flutter_radio_player"
        const val playbackEventChannelName = methodChannelName + "_playback_status_stream"
        const val metadataEventChannelName = methodChannelName + "_metadata_stream"
        const val volumeEventChannelName = methodChannelName+ "_volume_stream"
    }


    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        buildEngine(flutterPluginBinding.applicationContext, flutterPluginBinding.binaryMessenger)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        logger.info("Calling to method: " + call.method)
        when (call.method) {
            PlayerMethods.IS_PLAYING.value -> {
                val playStatus = isPlaying()
                result.success(playStatus)
            }
            PlayerMethods.PLAY_PAUSE.value -> {
                playOrPause()
                result.success(null)
            }
            PlayerMethods.PLAY.value -> {
                play()
                result.success(null)
            }
            PlayerMethods.NEW_PLAY.value -> {
                newPlay()
                result.success(null)
            }
            PlayerMethods.PAUSE.value -> {
                pause()
                result.success(null)
            }
            PlayerMethods.STOP.value -> {
                stop()
                result.success(null)
            }
            PlayerMethods.SET_TITLE.value -> {
                val title = call.argument<String>("title")!!
                val subTitle = call.argument<String>("subtitle")!!
                updateTitle(title, subTitle)
                result.success(null)
            }
            PlayerMethods.INIT.value -> {
                init(call)
                result.success(null)
            }
            PlayerMethods.SET_VOLUME.value -> {
                val volume = call.argument<Double>("volume")!!
                setVolume(volume)
                result.success(null)
            }
            PlayerMethods.SET_URL.value -> {
                val url = call.argument<String>("streamUrl")!!
                val playWhenReady = call.argument<String>("playWhenReady")!!

                setUrl(url, playWhenReady)
            }
            PlayerMethods.RE_EMMIT_STATES.value -> {
                launchPlayerIntentWithAction(StreamingCore.ACTION_RE_EMMIT_EVENTS)
            }
            else -> result.notImplemented()
        }

    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        applicationContext = binding.applicationContext
        launchPlayerIntentWithAction(StreamingCore.ACTION_DESTROY)
        LocalBroadcastManager.getInstance(applicationContext).unregisterReceiver(broadcastReceiver)
    }


    private fun buildEngine(context: Context, messenger: BinaryMessenger) {

        methodChannel = MethodChannel(messenger, methodChannelName)
        methodChannel.setMethodCallHandler(this)

        applicationContext = context

        initEventChannelStatus(messenger)
        initEventChannelMetaData(messenger)
        initEventChannelVolume(messenger)

        LocalBroadcastManager.getInstance(context).registerReceiver(broadcastReceiver, intentFilter)
    }

    private fun initEventChannelStatus(messenger: BinaryMessenger) {
        val eventChannel = EventChannel(messenger, playbackEventChannelName)
        eventChannel.setStreamHandler(object : StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink?) {
                mEventPlaybackStatusSink = events
            }

            override fun onCancel(arguments: Any?) {
                mEventPlaybackStatusSink = null
            }
        })
    }

    private fun initEventChannelMetaData(messenger: BinaryMessenger) {
        val eventChannel = EventChannel(messenger, metadataEventChannelName)
        eventChannel.setStreamHandler(object : StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink?) {
                mEventMetaDataSink = events
            }

            override fun onCancel(arguments: Any?) {
                mEventMetaDataSink = null
            }
        })
    }

    private fun initEventChannelVolume(messenger: BinaryMessenger) {
        val eventChannel = EventChannel(messenger, volumeEventChannelName)
        eventChannel.setStreamHandler(object : StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink?) {
                mEventVolumeSink = events
            }

            override fun onCancel(arguments: Any?) {
                mEventVolumeSink = null
            }
        })
    }


    private fun buildPlayerDetailsMeta(methodCall: MethodCall): PlayerItem {
        val url = methodCall.argument<String>("streamURL")
        val appName = methodCall.argument<String>("appName")
        val subTitle = methodCall.argument<String>("subTitle")
        val playWhenReady = methodCall.argument<String>("playWhenReady")
        val coverImageUrl = methodCall.argument<String>("coverImageUrl")

        return PlayerItem(appName!!, subTitle!!, url!!, playWhenReady!!, coverImageUrl)
    }

    /*===========================
     *     Player methods
     *===========================
     */

    private fun init(methodCall: MethodCall) {

        if (!isBound) {
            logger.info("Service not bound, binding now....")
            val serviceIntent = createInitIntentData(Intent(applicationContext, StreamingCore::class.java), buildPlayerDetailsMeta(methodCall))
            applicationContext.bindService(serviceIntent, serviceConnection, Context.BIND_IMPORTANT)
            applicationContext.startService(serviceIntent)
        } else {
            Timer("SettingUp", false).schedule(500) {
                launchPlayerIntentWithAction(StreamingCore.ACTION_RE_EMMIT_EVENTS)
            }
        }
    }


    private fun isPlaying(): Boolean {
        return coreService?.isPlaying() ?: false
    }

    private fun playOrPause() {
        launchPlayerIntentWithAction(StreamingCore.ACTION_TOGGLE_PLAYER)
    }


    private fun play() {
        launchPlayerIntentWithAction(StreamingCore.ACTION_PLAY)
    }

    private fun newPlay() {
        launchPlayerIntentWithAction(StreamingCore.ACTION_NEW_PLAYER)
    }

    private fun pause() {
        launchPlayerIntentWithAction(StreamingCore.ACTION_PAUSE)
    }

    private fun stop() {
        isBound = false
        applicationContext.unbindService(serviceConnection)
        launchPlayerIntentWithAction(StreamingCore.ACTION_STOP)
    }

    private fun setUrl(streamUrl: String, playWhenReady: String) {
        val playStatus: Boolean = playWhenReady == "true"
        val intent = Intent(applicationContext, StreamingCore::class.java).apply {
            putExtra("streamUrl", streamUrl)
            putExtra("playWhenReady", playStatus)
            action = StreamingCore.ACTION_UPDATE_STREAM_URL
        }
        applicationContext.startService(intent)
    }

    private fun setVolume(volume: Double) {
        val intent = Intent(applicationContext, StreamingCore::class.java).apply {
            putExtra("volume", volume)
            action = StreamingCore.ACTION_UPDATE_VOLUME
        }
        applicationContext.startService(intent)
    }

    private fun updateTitle(title: String, subTitle: String) {
        val intent = Intent(applicationContext, StreamingCore::class.java).apply {
            putExtra("appName", title)
            putExtra("subTitle", subTitle)
            action = StreamingCore.ACTION_UPDATE_TITLE
        }
        applicationContext.startService(intent)

    }

    /**
     * Build the player meta information for Stream service
     */
    private fun createInitIntentData(intent: Intent, playerItem: PlayerItem): Intent {
        intent.putExtra("streamUrl", playerItem.streamUrl)
        intent.putExtra("appName", playerItem.appName)
        intent.putExtra("subTitle", playerItem.subTitle)
        intent.putExtra("playWhenReady", playerItem.playWhenReady)
        intent.putExtra("coverImageUrl", playerItem.coverImageUrl)
        intent.action = StreamingCore.ACTION_INIT_PLAYER
        return intent
    }

    private fun launchPlayerIntentWithAction(action: String) {
        applicationContext.startService(Intent(applicationContext, StreamingCore::class.java).also { it.action = action })
    }


    /**
     * Initializes the connection
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            // coreService = null
            logger.info("Service Disconnected...")
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as StreamingCore.LocalBinder
            coreService = localBinder.service
            coreService?.activityJavaClass = activityJavaClass
            isBound = true
            logger.info("Service Connection Established...")
            logger.info("Service bounded...")

        }
    }

    /**
     * Broadcast receiver
     */
    private var broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            if (intent != null) {
                when (intent.action ?: "") {
                    BROADCAST_ACTION_PLAYBACK_STATUS -> {
                        val returnStatus = intent.getStringExtra("status")
//                        logger.info("Received status: $returnStatus")
                        mEventPlaybackStatusSink?.success(returnStatus)
                    }
                    BROADCAST_ACTION_META_DATA -> {
                        val receivedMeta = intent.getStringExtra("meta_data")
//                        logger.info("Received meta: $receivedMeta")
                        mEventMetaDataSink?.success(receivedMeta)
                    }
                    BROADCAST_ACTION_VOLUME -> {
                        val receivedVolume = intent.getFloatExtra("volume", .5F)
//                        logger.info("Received volume: $receivedVolume")
                        mEventVolumeSink?.success(receivedVolume)
                    }
                }

            }
        }
    }


    override fun onDetachedFromActivity() {
    }

    override fun onReattachedToActivityForConfigChanges(p0: ActivityPluginBinding) {
    }

    override fun onAttachedToActivity(p0: ActivityPluginBinding) {
        this.activityJavaClass = p0.activity.javaClass
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }
}
