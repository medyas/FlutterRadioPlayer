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
import android.media.session.MediaSession
import android.net.Uri
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.Nullable
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.icy.IcyInfo
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
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
    private lateinit var dataSourceFactory: DefaultDataSourceFactory
    private val localBroadcastManager = LocalBroadcastManager.getInstance(this@StreamingCore)

    // class instances
    private val handler = Handler();

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var player: SimpleExoPlayer? = null
    private var mediaSessionConnector: MediaSessionConnector? = null
    private var mediaSession: MediaSession? = null
    private var playerNotificationManager: PlayerNotificationManager? = null

    var notificationTitle = ""
    var notificationSubTitle = ""

    var wasPlaying: Boolean = false

    // session keys
    private val playbackNotificationId = 1025

    companion object {

        const val MANIFEST_NOTIFICATION_PLACEHOLDER = "flutter.radio.player.notification.placeholder"
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


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent != null) {
            when (intent.action) {
                ACTION_INIT_PLAYER -> {
                    if (player != null && isPlaying()) {
                        reEmmitEvents()
                        Handler(Looper.getMainLooper()).postDelayed({ reEmmitEvents() }, 1500)
                        return START_STICKY
                    }

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
                        onDestroy()
                    }
                }
                ACTION_NEW_PLAYER -> newPlay()
                ACTION_IS_PLAYING -> {
                    reEmmitPlaybackStatus()
                }
                ACTION_RE_EMMIT_EVENTS -> reEmmitEvents()
                ACTION_UPDATE_TITLE -> setTitle(intent.getStringExtra("appName")
                        ?: "", intent.getStringExtra("subTitle") ?: "")
                ACTION_UPDATE_STREAM_URL -> setUrl(intent.getStringExtra("streamUrl")
                        ?: "", intent.getStringExtra("playWhenReady") == "true")
                else -> {

                }
            }
        }

        return START_STICKY
    }

    /*===========================
     *        Player APIS
     *===========================
     */

    private fun play() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager!!.requestAudioFocus(focusRequest!!)
        } else {
            audioManager!!.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        player?.seekToDefaultPosition()
        player?.playWhenReady = true
        wasPlaying = false
    }

    private fun newPlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager!!.requestAudioFocus(focusRequest!!)
        } else {
            audioManager!!.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        player?.stop()
        player?.prepare()
        player?.playWhenReady = true
        wasPlaying = false

    }

    private fun pause() {
        player?.playWhenReady = false
    }

    fun isPlaying(): Boolean {
        return playbackStatus == PlaybackStatus.PLAYING
    }

    private fun stop() {
        player?.stop()
        stopSelf()
        isBound = false
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

    private var delayedStopRunnable = Runnable {
//        stop()
    }


    private fun initStreamPlayer(streamUrl: String, playWhenReady: Boolean, coverImageUrl: String?) {
        player = SimpleExoPlayer
                .Builder(this@StreamingCore)
                .setMediaSourceFactory(
                        DefaultMediaSourceFactory(this@StreamingCore).setLiveTargetOffsetMs(1000))
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

        dataSourceFactory = DefaultDataSourceFactory(this@StreamingCore, Util.getUserAgent(this@StreamingCore, notificationTitle))

        val audioSource = buildMediaSource(dataSourceFactory, streamUrl)

        // set exo player configs
        player?.let {
            it.addListener(createPlayerEventListener())
            it.playWhenReady = playWhenReady
            it.setMediaSource(audioSource)
            it.prepare()
        }

        // register our meta data listener
        player?.addMetadataOutput { metadata ->
            processIcyMetaData(metadata)
        }

        createPlayerNotificationManager(coverImageUrl)

        val mediaSession = MediaSessionCompat(this@StreamingCore, mediaSessionId)
        mediaSession.isActive = true

        mediaSessionConnector = MediaSessionConnector(mediaSession)
        mediaSessionConnector?.setPlayer(player)

//        val dispatcher = CustomControlDispatcher(0, 0)
//        playerNotificationManager?.setControlDispatcher(dispatcher)

        playerNotificationManager?.setUseStopAction(true)
        playerNotificationManager?.setUsePlayPauseActions(true)
        playerNotificationManager?.setPlayer(player)
        playerNotificationManager?.setMediaSessionToken(mediaSession.sessionToken)
        val resId = loadLocalDrawable(MANIFEST_NOTIFICATION_ICON)
        if (resId != null)
            playerNotificationManager?.setSmallIcon(resId)

        playbackStatus = PlaybackStatus.PLAYING
    }

    private fun processIcyMetaData(metadata: Metadata) {
        val length = metadata.length()
        if (length > 0) {
            val entry = metadata[0]
            if (entry is IcyInfo) {
                val icyInfo = entry as IcyInfo
                val info = icyInfo.title
                        ?.split(" - ")
                        ?.map { it.trim().replace("-", " ") }
                        ?.filter { it.isNotEmpty() }
                info?.let {
//                    logger.info("MetadataOutput ---------- ${info.joinToString(separator = " - ")}")

                    notificationTitle = if (info.isNotEmpty()) info[0] else ""
                    notificationSubTitle = if (info.size > 1) info.subList(1, info.size).joinToString(separator = " - ") else ""

                    playerNotificationManager?.invalidate()
                    reEmmitMetaData()
                }
            }
        }
    }

    private fun createPlayerEventListener(): Player.EventListener {
        return object : Player.EventListener {

            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        this@StreamingCore.audioManager!!.requestAudioFocus(this@StreamingCore.focusRequest!!)
                    } else {
                        this@StreamingCore.audioManager!!.requestAudioFocus(this@StreamingCore, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                    }
                } else {
                    logger.info("Remove player as a foreground notification...")
                    stopForeground(false)
                }
                logger.info("onPlayerStateChanged: $playbackStatus")

            }

            override fun onPlayerError(error: ExoPlaybackException) {
                pushEvent(FLUTTER_RADIO_PLAYER_ERROR)
                playbackStatus = PlaybackStatus.ERROR
                error.printStackTrace()
            }
        }
    }

    private fun createPlayerNotificationManager(coverImageUrl: String?) {
        playerNotificationManager = PlayerNotificationManager.createWithNotificationChannel(
                this@StreamingCore,
                playbackChannelId,
                R.string.channel_name,
                R.string.channel_description,
                playbackNotificationId,
                object : PlayerNotificationManager.MediaDescriptionAdapter {

                    override fun getCurrentContentTitle(player: Player): String {
                        return notificationTitle
                    }

                    @Nullable
                    override fun createCurrentContentIntent(player: Player): PendingIntent {
                        val i = Intent(this@StreamingCore, activityJavaClass!!)
                        val contentPendingIntent = PendingIntent.getActivity(this@StreamingCore, 0, i, 0);
                        return contentPendingIntent
                    }

                    @Nullable
                    override fun getCurrentContentText(player: Player): String? {
                        return notificationSubTitle
                    }

                    @Nullable
                    override fun getCurrentLargeIcon(player: Player, callback: PlayerNotificationManager.BitmapCallback): Bitmap? {
                        if (coverImageUrl != null) {
                            loadCoverImageFromUrl(coverImageUrl, callback)
                        }

                        return loadLocalBitmap(MANIFEST_NOTIFICATION_PLACEHOLDER)
                    }

                },
                object : PlayerNotificationManager.NotificationListener {
                    override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                        stop()
                    }

                    override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
                        startForeground(notificationId, notification)
//                        if (!ongoing) {
//                            stopForeground(false)
//                        }
                    }
                }
        )
    }

    private fun loadCoverImageFromUrl(coverImageUrl: String, callback: PlayerNotificationManager.BitmapCallback) {
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

                    override fun onResourceReady(resource: Bitmap, transition: com.bumptech.glide.request.transition.Transition<in Bitmap>?) {
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
            audioManager!!.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return iBinder
    }

    override fun onDestroy() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession?.release()
        }

        mediaSessionConnector?.setPlayer(null)
        playerNotificationManager?.setPlayer(null)
        player?.release()
        stopForeground(true)

        super.onDestroy()
    }

    override fun onAudioFocusChange(audioFocus: Int) {
        when (audioFocus) {

            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.volume = 0.5f
                if (wasPlaying) {
                    newPlay()
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
        localBroadcastManager.sendBroadcast(Intent(BROADCAST_ACTION_META_DATA).putExtra("meta_data", "$notificationTitle:$notificationSubTitle"))
    }

    private fun reEmmitVolume() {
        localBroadcastManager.sendBroadcast(Intent(BROADCAST_ACTION_VOLUME).putExtra("volume", player?.volume))
    }

    private fun pushEvent(eventName: String) {
//        logger.info("Pushing Event: $eventName")
        localBroadcastManager.sendBroadcast(Intent(BROADCAST_ACTION_PLAYBACK_STATUS).putExtra("status", eventName))
    }

    /**
     * Build the media source depending of the URL content type.
     */
    private fun buildMediaSource(dataSourceFactory: DefaultDataSourceFactory, streamUrl: String): MediaSource {

        val uri = Uri.parse(streamUrl)

        return when (val type = Util.inferContentType(uri)) {
//            C.TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri))
            C.TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri))
            C.TYPE_OTHER -> ProgressiveMediaSource.Factory(dataSourceFactory)
//                    .setContinueLoadingCheckIntervalBytes(1024*32)
                    .createMediaSource(MediaItem.fromUri(uri))
            else -> {
                throw IllegalStateException("Unsupported type: $type")
            }
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
