import 'dart:async';

import 'package:flutter/services.dart';

class FlutterRadioPlayer {
  static const _channelName = 'flutter_radio_player';
  static const MethodChannel _channel = const MethodChannel(_channelName);

  static const EventChannel _eventChannel =
      const EventChannel("${_channelName}_playback_status_stream");

  static const EventChannel _eventChannelMetaData =
      const EventChannel("${_channelName}_metadata_stream");

  static const EventChannel _eventChannelVolume =
      const EventChannel("${_channelName}_volume_stream");

  // constants to support event channel
  static const flutter_radio_stopped = "flutter_radio_stopped";
  static const flutter_radio_playing = "flutter_radio_playing";
  static const flutter_radio_paused = "flutter_radio_paused";
  static const flutter_radio_error = "flutter_radio_error";
  static const flutter_radio_loading = "flutter_radio_loading";

  static Stream<String?>? _isPlayingStream;
  static Stream<String?>? _metaDataStream;
  static Stream<double?>? _volumeStream;

  Future<void> init(
      String appName, String subTitle, String streamURL, String playWhenReady,
      String coverImageUrl) async {
    return await _channel.invokeMethod("initService", {
      "appName": appName,
      "subTitle": subTitle,
      "streamURL": streamURL,
      "playWhenReady": playWhenReady,
      "coverImageUrl": coverImageUrl
    });
  }

  Future<bool?> play() async {
    return await _channel.invokeMethod("play");
  }

  Future<bool?> newPlay() async {
    return await _channel.invokeMethod("newPlay");
  }

  Future<bool?> pause() async {
    return await _channel.invokeMethod("pause");
  }

  Future<bool?> playOrPause() async {
    return await _channel.invokeMethod("playOrPause");
  }

  Future<bool?> stop() async {
    return await _channel.invokeMethod("stop");
  }

  Future<bool?> isPlaying() async {
    bool? isPlaying = await _channel.invokeMethod("isPlaying");
    return isPlaying;
  }

  Future<void> setVolume(double volume) async {
    await _channel.invokeMethod("setVolume", {"volume": volume});
  }

  Future<void> setTitle(String title, String subtitle) async {
    await _channel
        .invokeMethod("setTitle", {"title": title, "subtitle": subtitle});
  }

  Future<void> setUrl(String streamUrl, String playWhenReady) async {
    await _channel.invokeMethod("setUrl", {
      "playWhenReady": playWhenReady,
      "streamUrl": streamUrl,
    });
  }

  Future<void> reEmmitStates() async {
    await _channel.invokeMethod("reEmmitStates");
  }

  /// Get the player stream.
  Stream<String?>? get isPlayingStream {
    if (_isPlayingStream == null) {
      _isPlayingStream =
          _eventChannel.receiveBroadcastStream().map<String?>((value) => value);
    }
    return _isPlayingStream;
  }

  Stream<String?>? get metaDataStream {
    if (_metaDataStream == null) {
      _metaDataStream = _eventChannelMetaData
          .receiveBroadcastStream()
          .map<String?>((value) => value);
    }

    return _metaDataStream;
  }

  Stream<double?>? get volumeStream {
    if (_volumeStream == null) {
      _volumeStream = _eventChannelVolume
          .receiveBroadcastStream()
          .map<double?>((value) => value);
    }

    return _volumeStream;
  }
}

/// Flutter_radio_playback status
enum PlaybackStatus {
  flutter_radio_stopped,
  flutter_radio_playing,
  flutter_radio_paused,
  flutter_radio_error,
  flutter_radio_loading,
}
