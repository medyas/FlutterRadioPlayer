package me.sithiramunasinghe.flutter.flutter_radio_player.core

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.session.MediaButtonReceiver
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.icy.IcyInfo
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.util.Util
import me.sithiramunasinghe.flutter.flutter_radio_player.FlutterRadioPlayerPlugin.Companion.BROADCAST_ACTION_META_DATA
import me.sithiramunasinghe.flutter.flutter_radio_player.FlutterRadioPlayerPlugin.Companion.BROADCAST_ACTION_PLAYBACK_STATUS
import me.sithiramunasinghe.flutter.flutter_radio_player.FlutterRadioPlayerPlugin.Companion.BROADCAST_ACTION_VOLUME
import me.sithiramunasinghe.flutter.flutter_radio_player.R
import me.sithiramunasinghe.flutter.flutter_radio_player.core.enums.PlaybackStatus
import java.lang.Exception
import java.util.logging.Logger


class StreamingCore : Service(), AudioManager.OnAudioFocusChangeListener {

    private var logger = Logger.getLogger(StreamingCore::javaClass.name)
    var activityJavaClass: Class<Activity>? = null

    private var isBound = false
    private val iBinder = LocalBinder()
    private lateinit var playbackStatus: PlaybackStatus
    private lateinit var dataSourceFactory: DefaultDataSource.Factory
    private lateinit var localBroadcastManager: LocalBroadcastManager

    // class instances
    private val handler = Handler(Looper.getMainLooper())

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var player: ExoPlayer? = null
    private var mediaSessionConnector: MediaSessionConnector? = null
    private var mediaSessionCompat: MediaSessionCompat? = null
    private var playerNotificationManager: PlayerNotificationManager? = null

    var notificationTitle = ""
    var notificationSubTitle = ""

    private var wasPlaying: Boolean = false

    // session keys
    private val playbackNotificationId = 1025

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            onIntentReceive(mediaButtonEvent)
            return super.onMediaButtonEvent(mediaButtonEvent)
        }
    }

    companion object {

        const val MANIFEST_NOTIFICATION_PLACEHOLDER =
            "flutter.radio.player.notification.placeholder"
        const val MANIFEST_NOTIFICATION_ICON = "flutter.radio.player.notification.icon"

        private const val mediaSessionId = "streaming_audio_player_media_session"
        private const val playbackChannelId = "streaming_audio_player_channel_id"

        const val ACTION_INIT_PLAYER = "action_init_player"
        const val ACTION_UPDATE_VOLUME = "action_update_volume"
        const val ACTION_TOGGLE_PLAYER = "action_toggle_player"
        const val ACTION_PLAY = "action_play"
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_STOP = "action_stop"
        const val ACTION_DESTROY = "action_destroy"
        const val ACTION_IS_PLAYING = "action_is_playing"
        const val ACTION_UPDATE_TITLE = "action_update_title"
        const val ACTION_UPDATE_STREAM_URL = "action_update_stream_url"
        const val ACTION_NEW_PLAYER = "action_new_player"
        const val ACTION_RE_EMMIT_EVENTS = "action_re_emmit_events"

    }

    inner class LocalBinder : Binder() {
        internal val service: StreamingCore
            get() = this@StreamingCore
    }

    override fun onCreate() {
        super.onCreate()
        localBroadcastManager = LocalBroadcastManager.getInstance(this@StreamingCore)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        logger.info("onStartCommand: ${intent?.action}")

        MediaButtonReceiver.handleIntent(mediaSessionCompat, intent)

        handleIntent(intent)

        return START_STICKY
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null) {
            when (intent.action) {
                ACTION_INIT_PLAYER -> {
                    logger.info("onStartCommand: $ACTION_INIT_PLAYER - ${player == null} ")
                    if (player != null && isPlaying()) {
                        reEmmitEvents()
                        Handler(Looper.getMainLooper()).postDelayed({
                            reEmmitEvents()
                        }, 1500)
                    }

                    logger.info("$ACTION_INIT_PLAYER not playing ! ======>")

                    notificationTitle = intent.getStringExtra("appName") ?: ""
                    notificationSubTitle = intent.getStringExtra("subTitle") ?: ""
                    val streamUrl = intent.getStringExtra("streamUrl") ?: ""
                    val playWhenReady = intent.getStringExtra("playWhenReady") == "true"
                    val coverImageUrl = intent.getStringExtra("coverImageUrl")

                    initStreamPlayer(streamUrl, playWhenReady, coverImageUrl)
                }
                ACTION_UPDATE_VOLUME -> {
                    val volume = intent.getDoubleExtra("volume", .5)
                    setVolume(volume)
                }
                ACTION_TOGGLE_PLAYER -> if (isPlaying()) pause() else play()
                ACTION_PLAY -> play()
                ACTION_PAUSE -> pause()
                ACTION_STOP -> stop()
                ACTION_DESTROY -> {
                    if (player == null || !isPlaying()) {
                        stop()
                    }
                }
                ACTION_NEW_PLAYER -> newPlay()
                ACTION_IS_PLAYING -> {
                    reEmmitPlaybackStatus()
                }
                ACTION_RE_EMMIT_EVENTS -> reEmmitEvents()
                ACTION_UPDATE_TITLE -> setTitle(
                    intent.getStringExtra("appName")
                        ?: "", intent.getStringExtra("subTitle") ?: ""
                )
                ACTION_UPDATE_STREAM_URL -> setUrl(
                    intent.getStringExtra("streamUrl")
                        ?: "", intent.getStringExtra("playWhenReady") == "true"
                )
                else -> {

                }
            }
        }
    }

    private fun getAdjustedKeyCode(keyEvent: KeyEvent): Int {
        val keyCode = keyEvent.keyCode
        return if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        } else keyCode
    }

    private fun mapAction(keyCode: Int): String? {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> ACTION_PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> ACTION_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> ACTION_TOGGLE_PLAYER
            KeyEvent.KEYCODE_MEDIA_STOP -> ACTION_STOP
            else -> null
        }
    }

    fun onIntentReceive(intent: Intent?) {
        if (intent == null) {
            return
        }
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) {
            return
        }
        (intent.extras?.get(Intent.EXTRA_KEY_EVENT) as? KeyEvent)
            ?.takeIf { it.action == KeyEvent.ACTION_DOWN }
            ?.let { getAdjustedKeyCode(it) }
            ?.let { mapAction(it) }
            ?.let { action ->
                handleIntent(Intent(action))
            }

    }

    override fun onBind(intent: Intent?): IBinder {
        return iBinder
    }

    override fun onDestroy() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSessionCompat?.release()
        }

        mediaSessionConnector?.setPlayer(null)
        playerNotificationManager?.setPlayer(null)
        player?.release()
        stopForegroundService()

        super.onDestroy()
    }

    /*===========================
     *        Player APIS
     *===========================
     */

    private fun play() {
        requestAudioFocus()
//        player?.seekToDefaultPosition()
        player?.playWhenReady = true
        wasPlaying = false
    }

    private fun newPlay() {
        requestAudioFocus()
        player?.stop()
        player?.prepare()
        player?.playWhenReady = true
        wasPlaying = false

    }

    private fun pause() {
        player?.playWhenReady = false
    }

    fun isPlaying(): Boolean {
        return player?.isPlaying ?: false
//        return if (this@StreamingCore::playbackStatus.isInitialized)
//            playbackStatus == PlaybackStatus.PLAYING
//        else false
    }

    private fun stop() {
        player?.stop()
        isBound = false
        stopSelf()
    }

    private fun setTitle(title: String, subTitle: String) {
        this.notificationTitle = title
        this.notificationSubTitle = subTitle
        playerNotificationManager?.invalidate()
    }

    private fun setVolume(volume: Double) {
        player?.volume = volume.toFloat()
        reEmmitVolume()
    }

    private fun setUrl(streamUrl: String, playWhenReady: Boolean) {
        player?.setMediaSource(buildMediaSource(dataSourceFactory, streamUrl))
        player?.prepare()
        player?.playWhenReady = playWhenReady
    }

    private fun initStreamPlayer(
        streamUrl: String,
        playWhenReady: Boolean,
        coverImageUrl: String?
    ) {
        player = ExoPlayer
            .Builder(this@StreamingCore)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this@StreamingCore)/*.setLiveTargetOffsetMs(1000)*/
            )
//                .setLoadControl(CustomLoadControl
//                        .Builder()
//                        .setPrioritizeTimeOverSizeThresholds(true)
//                        .setBufferDurationsMs(10000,
//                                10000,
//                                10000,
//                                10000)
//                        .build()
//                )
            .build()

        player?.volume = 0.5f

        setupAudioFocus()

        dataSourceFactory = DefaultDataSource.Factory(
            this@StreamingCore,
//            Util.getUserAgent(this@StreamingCore, notificationTitle)
        )

        val audioSource = buildMediaSource(dataSourceFactory, streamUrl)

        // set exo player configs
        player?.let {
            it.addListener(createPlayerEventListener())
            it.playWhenReady = playWhenReady
            it.setMediaSource(audioSource)
            it.prepare()
        }

        createPlayerNotificationManager(coverImageUrl)

        mediaSessionCompat = MediaSessionCompat(this@StreamingCore, mediaSessionId)
        mediaSessionCompat?.isActive = true
        mediaSessionCompat?.setCallback(mediaSessionCallback)

        mediaSessionConnector = MediaSessionConnector(mediaSessionCompat!!)
        mediaSessionConnector?.setPlayer(player)

//        val dispatcher = CustomControlDispatcher(0, 0)
//        playerNotificationManager?.setControlDispatcher(dispatcher)

        playerNotificationManager?.setUseStopAction(true)
        playerNotificationManager?.setUsePlayPauseActions(true)
        playerNotificationManager?.setPlayer(player)
        playerNotificationManager?.setMediaSessionToken(mediaSessionCompat!!.sessionToken)
        val resId = loadLocalDrawable(MANIFEST_NOTIFICATION_ICON)
        if (resId != null)
            playerNotificationManager?.setSmallIcon(resId)

        if (playWhenReady)
            playbackStatus = if (playWhenReady) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED
    }

    private fun createPlayerEventListener(): Player.Listener {
        return object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                super.onMediaMetadataChanged(mediaMetadata)
                // register our meta data listener
                processMetaData(mediaMetadata.title.toString(), mediaMetadata.subtitle.toString())
            }

            override fun onMetadata(metadata: Metadata) {
                super.onMetadata(metadata)
                // register our meta data listener
                processIcyMetaData(metadata)
            }

            @Deprecated("Deprecated in Java")
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                logger.info("onPlayerStateChanged:  $playbackState")
                playbackStatus = when (playbackState) {
                    Player.STATE_ENDED -> {
                        pushEvent(FLUTTER_RADIO_PLAYER_STOPPED)
                        PlaybackStatus.STOPPED
                    }
                    Player.STATE_BUFFERING -> {
                        pushEvent(FLUTTER_RADIO_PLAYER_LOADING)
                        PlaybackStatus.LOADING
                    }
                    Player.STATE_IDLE -> {
                        pushEvent(FLUTTER_RADIO_PLAYER_STOPPED)
                        PlaybackStatus.STOPPED
                    }
                    Player.STATE_READY -> {
                        setPlayWhenReady(playWhenReady)
                    }
                    else -> if (this@StreamingCore::playbackStatus.isInitialized) this@StreamingCore.playbackStatus else PlaybackStatus.STOPPED

                }
                if (playbackStatus == PlaybackStatus.PLAYING) {
                    requestAudioFocus()
                } else {
                    logger.info("Remove player as a foreground notification...")

                    stopForegroundService()
                }
                logger.info("onPlayerStateChanged: $playbackStatus")

            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                pushEvent(FLUTTER_RADIO_PLAYER_ERROR)
                playbackStatus = PlaybackStatus.ERROR
                error.printStackTrace()
            }


        }
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            stopForeground(true)
        }
    }

    private fun processIcyMetaData(metadata: Metadata) {
        val length = metadata.length()
        if (length > 0) {
            val entry = metadata[0]
            if (entry is IcyInfo) {
                val info = entry.title
                    ?.split(" - ")
                    ?.map { it.trim().replace("-", " ") }
                    ?.filter { it.isNotEmpty() }
                info?.let {

                    val title = if (info.isNotEmpty()) info[0] else ""
                    val subTitle = if (info.size > 1) info.subList(1, info.size)
                        .joinToString(separator = " - ") else ""

                    processMetaData(title, subTitle)
                }
            }
        }
    }

    private fun processMetaData(title: String, subTitle: String) {
        notificationTitle = title
        notificationSubTitle = subTitle

        playerNotificationManager?.invalidate()
        reEmmitMetaData()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this@StreamingCore.audioManager!!.requestAudioFocus(this@StreamingCore.focusRequest!!)
        } else {
            this@StreamingCore.audioManager!!.requestAudioFocus(
                this@StreamingCore,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun createPlayerNotificationManager(coverImageUrl: String?) {
        playerNotificationManager = PlayerNotificationManager.Builder(
            this@StreamingCore,
            playbackNotificationId,
            playbackChannelId,
        )
            .setChannelNameResourceId(R.string.channel_name)
            .setChannelDescriptionResourceId(R.string.channel_description)
            .setNotificationListener(
                object : PlayerNotificationManager.NotificationListener {
                    override fun onNotificationCancelled(
                        notificationId: Int,
                        dismissedByUser: Boolean
                    ) {
                        stop()
                    }

                    override fun onNotificationPosted(
                        notificationId: Int,
                        notification: Notification,
                        ongoing: Boolean
                    ) {
                        startForeground(notificationId, notification)
                    }
                },
            )
            .setMediaDescriptionAdapter(
                object : PlayerNotificationManager.MediaDescriptionAdapter {

                    override fun getCurrentContentTitle(player: Player): String {
                        return notificationTitle
                    }

                    override fun createCurrentContentIntent(player: Player): PendingIntent {
                        val i = Intent(this@StreamingCore, activityJavaClass!!)
                        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            PendingIntent.getActivity(
                                this@StreamingCore,
                                0,
                                i,
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        } else {
                            PendingIntent.getActivity(
                                this@StreamingCore,
                                0,
                                i,
                                0
                            )
                        }
                    }

                    override fun getCurrentContentText(player: Player): String {
                        return notificationSubTitle
                    }

                    override fun getCurrentLargeIcon(
                        player: Player,
                        callback: PlayerNotificationManager.BitmapCallback
                    ): Bitmap? {
                        if (coverImageUrl != null) {
                            loadCoverImageFromUrl(coverImageUrl, callback)
                        }

                        return loadLocalBitmap(MANIFEST_NOTIFICATION_PLACEHOLDER)
                    }

                },
            )
            .build()
    }

    private fun loadCoverImageFromUrl(
        coverImageUrl: String,
        callback: PlayerNotificationManager.BitmapCallback
    ) {
        Glide.with(this@StreamingCore)
            .asBitmap()
            .timeout(5000)
            .load(coverImageUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    try {
                        val placeHolder = loadLocalBitmap(MANIFEST_NOTIFICATION_PLACEHOLDER)
                        if (placeHolder != null) {
                            callback.onBitmap(placeHolder)
                        } else {
                            throw Exception("Failed to load placeholder !")
                        }
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        logger.info("Bitmap Error: ${t.message}")
                    }
                }

                override fun onResourceReady(
                    resource: Bitmap,
                    transition: com.bumptech.glide.request.transition.Transition<in Bitmap>?
                ) {
                    callback.onBitmap(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {

                }
            })
    }

    private fun loadLocalDrawable(path: String): Int? {
        val appInfos = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        return appInfos.metaData.get(path) as? Int
    }

    private fun loadLocalBitmap(path: String): Bitmap? {
        val manifestPlaceHolderResource = loadLocalDrawable(path)
        return if (manifestPlaceHolderResource == null) {
            null
        } else {
            BitmapFactory.decodeResource(resources, manifestPlaceHolderResource)
        }
    }

    private fun setupAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(android.media.AudioAttributes.Builder().run {
                    setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    build()
                })
                setAcceptsDelayedFocusGain(true)
                setOnAudioFocusChangeListener(this@StreamingCore, handler)
                build()
            }
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager!!.requestAudioFocus(it) }
        } else {
            audioManager!!.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    override fun onAudioFocusChange(audioFocus: Int) {
        logger.info("onAudioFocusChange: $audioFocus")

        when (audioFocus) {

            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.volume = 0.5f
                if (wasPlaying) {
//                    newPlay()
                    play()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                if (isPlaying()) {
                    pause()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying()) {
                    pause()
                    wasPlaying = true
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPlaying()) {
                    player?.volume = 0.1f
                }
            }
        }
    }

    /**
     * ReEmmit status
     * */
    private fun reEmmitEvents() {
        logger.info("reEmmtSatus ===========================>")
        reEmmitPlaybackStatus()
        reEmmitVolume()
        reEmmitMetaData()
    }

    /**
     * Push events to local broadcaster service.
     */
    private fun reEmmitPlaybackStatus() {
        if (this::playbackStatus.isInitialized) {
            playbackStatus = when (playbackStatus) {
                PlaybackStatus.PAUSED -> {
                    pushEvent(FLUTTER_RADIO_PLAYER_PAUSED)
                    PlaybackStatus.PAUSED
                }
                PlaybackStatus.PLAYING -> {
                    pushEvent(FLUTTER_RADIO_PLAYER_PLAYING)
                    PlaybackStatus.PLAYING
                }
                PlaybackStatus.LOADING -> {
                    pushEvent(FLUTTER_RADIO_PLAYER_LOADING)
                    PlaybackStatus.LOADING

                }
                PlaybackStatus.STOPPED -> {
                    pushEvent(FLUTTER_RADIO_PLAYER_STOPPED)
                    PlaybackStatus.STOPPED
                }
                PlaybackStatus.ERROR -> {
                    pushEvent(FLUTTER_RADIO_PLAYER_ERROR)
                    PlaybackStatus.ERROR
                }
            }
        }
    }

    private fun reEmmitMetaData() {
        localBroadcastManager.sendBroadcast(
            Intent(BROADCAST_ACTION_META_DATA).putExtra(
                "meta_data",
                "$notificationTitle:$notificationSubTitle"
            )
        )
    }

    private fun reEmmitVolume() {
        localBroadcastManager.sendBroadcast(
            Intent(BROADCAST_ACTION_VOLUME).putExtra(
                "volume",
                player?.volume
            )
        )
    }

    private fun pushEvent(eventName: String) {
        logger.info("Pushing Event: $eventName")
        localBroadcastManager.sendBroadcast(
            Intent(BROADCAST_ACTION_PLAYBACK_STATUS).putExtra(
                "status",
                eventName
            )
        )
    }

    /**
     * Build the media source depending of the URL content type.
     */
    private fun buildMediaSource(
        dataSourceFactory: DefaultDataSource.Factory,
        streamUrl: String
    ): MediaSource {

        val uri = Uri.parse(streamUrl)

        return when (Util.inferContentType(uri)) {
            C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(uri))
            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(uri))
            else -> ProgressiveMediaSource.Factory(dataSourceFactory)
//                    .setContinueLoadingCheckIntervalBytes(1024*32)
                .createMediaSource(MediaItem.fromUri(uri))

        }
    }

    private fun setPlayWhenReady(playWhenReady: Boolean): PlaybackStatus {
        return if (playWhenReady) {
            pushEvent(FLUTTER_RADIO_PLAYER_PLAYING)
            PlaybackStatus.PLAYING
        } else {
            pushEvent(FLUTTER_RADIO_PLAYER_PAUSED)
            PlaybackStatus.PAUSED
        }
    }

}
