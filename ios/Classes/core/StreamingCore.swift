//
//  StreamingCore.swift
//  flutter_radio_player
//
//  Created by Sithira on 3/26/20.
//

import Foundation
import AVFoundation
import MediaPlayer

class StreamingCore : NSObject, AVPlayerItemMetadataOutputPushDelegate {
    
    private var avPlayer: AVPlayer?
    private var avPlayerItem: AVPlayerItem?
    private var playerItemContext = 0
    private var commandCenter: MPRemoteCommandCenter?
    private var playWhenReady: Bool = false
    private var wasPlaying: Bool = false
    
    private var nowPlayingInfo = [String: Any]()

    private var streamUrl:String = ""
    
    var playerStatus: String = Constants.FLUTTER_RADIO_STOPPED

    
    override init() {
        print("StreamingCore Initializing...")
    }
    
    deinit {
        print("StreamingCore Deinitializing...")
        
        self.avPlayer?.removeObserver(self, forKeyPath: #keyPath(AVPlayer.status))
        self.avPlayer?.removeObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem.status))
        self.avPlayer?.removeObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem.isPlaybackBufferEmpty))
    }
    
    fileprivate func removeAvPlayerObserversIfSubscribed() {
        if !isFirstTime {
            
            NotificationCenter.default.removeObserver(self, name: .AVPlayerItemNewErrorLogEntry, object: nil)
            NotificationCenter.default.removeObserver(self, name: .AVPlayerItemFailedToPlayToEndTime, object: nil)
            NotificationCenter.default.removeObserver(self, name: .AVPlayerItemPlaybackStalled, object: nil)
            
            self.avPlayer?.removeObserver(self, forKeyPath: #keyPath(AVPlayer.status))
            self.avPlayer?.removeObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem.status))
            self.avPlayer?.removeObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem.isPlaybackBufferEmpty))
            
        }
    }
    
    func initService(streamURL: String, serviceName: String, secondTitle: String, playWhenReady: String, coverImageUrl: String) -> Void {
        self.streamUrl = streamURL
        print("Initialing Service...")

        print("Stream url: " + streamURL)

        let streamURLInstance = URL(string: streamURL)
        removeAvPlayerObserversIfSubscribed()
        // Setting up AVPlayer
        avPlayerItem = AVPlayerItem(url: streamURLInstance!)
        avPlayer = AVPlayer(playerItem: avPlayerItem!)

        //Listener for metadata from streaming
        let metadataOutput = AVPlayerItemMetadataOutput(identifiers: nil)
        metadataOutput.setDelegate(self, queue: DispatchQueue.main)
        avPlayerItem?.add(metadataOutput)

        if playWhenReady == "true" {
            print("PlayWhenReady: true")
            self.playWhenReady = true
        }

        // initialize player observers
        initPlayerObservers()

        // init Remote protocols.
        initRemoteTransportControl(appName: serviceName, subTitle: secondTitle, coverImageUrl: coverImageUrl);
        setupNotifications()

        if #available(iOS 10.0, *) {
            avPlayerItem?.preferredForwardBufferDuration = 10
        }

        let notificationCenter = NotificationCenter.default
        if !isFirstTime{
            notificationCenter.removeObserver(self, name: UIApplication.didBecomeActiveNotification, object: nil)
        }
        notificationCenter.addObserver(self, selector: #selector(appMovedToForeground), name: UIApplication.didBecomeActiveNotification, object: nil)
        
        isFirstTime = false
    }

    @objc
    func appMovedToForeground() {
        print("Reemmiting the current state!")
        pushEvent(eventName: playerStatus)
    }
    
    var isFirstTime = true

    func setupNotifications() {
        // Get the default notification center instance.
        let nc = NotificationCenter.default
        if !isFirstTime{
            nc.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        }
        nc.addObserver(self,
                       selector: #selector(handleInterruption),
                       name: AVAudioSession.interruptionNotification,
                       object: nil)

    }

    @objc func handleInterruption(notification: Notification) {
        // To be implemented.
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue)
           else {
                  return
          }
      
          switch type {
          case .began:
            if #available(iOS 10.3, *) {
//                let suspendedKey = userInfo[AVAudioSessionInterruptionWasSuspendedKey] as? NSNumber ?? 0
//                if suspendedKey == 0 {
//                    wasPlaying = false
//                    _ = pause()
//                } else {
                    if playerStatus == Constants.FLUTTER_RADIO_PLAYING{
                        wasPlaying = true
                        _ = pause()
                    }
//                }
            } else {
                wasPlaying = false
                _ = pause()
                // Fallback on earlier versions
            }


            print("an intrruption has begun")
            break
          case .ended:
            if let optionValue = (notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.uintValue, AVAudioSession.InterruptionOptions(rawValue: optionValue) == .shouldResume {
                    _ = play()
                    wasPlaying = false
            }
          
            break
          default: ()
          }
    }
    
    func metadataOutput(_ output: AVPlayerItemMetadataOutput, didOutputTimedMetadataGroups groups: [AVTimedMetadataGroup], from track: AVPlayerItemTrack?) {
        
        if let item = (groups.first?.items.first) {
            let meta = item.value(forKeyPath: "value") as!  String
            let list = meta.split(separator: "-")
            if(list.count > 1) {
                let title = String(list[0])
                let subTitle = Array(list[1..<list.count]).joined(separator: "-")
                self.setTitle(title: title, subTitle: subTitle)
                
                pushEvent(typeEvent: "meta_data",eventName: title+":"+subTitle)
            } else {
                let title = String(list[0])
                let subTitle = ""
                self.setTitle(title: title, subTitle: subTitle)
                
                pushEvent(typeEvent: "meta_data",eventName: title+":"+subTitle)
            }
            
        }
        
    }
    
    @objc func systemVolumeDidChange(notification: NSNotification) {
            if let volume = notification.userInfo?["AVSystemController_AudioVolumeNotificationParameter"] as? Float {
            pushEvent(typeEvent:"volume", eventName: volume)
        }
    }
    
    func play() -> PlayerStatus {
        print("invoking play method on service")
        playerStatus = Constants.FLUTTER_RADIO_PLAYING
        if(!isPlaying()) {
            avPlayer?.play()
            pushEvent(eventName: Constants.FLUTTER_RADIO_PLAYING)
        }
        return PlayerStatus.PLAYING
    }
    
    func newPlay() -> PlayerStatus {
        print("invoking play method on service")
        playerStatus = Constants.FLUTTER_RADIO_PLAYING
        let streamURLInstance = URL(string: streamUrl)
        playWhenReady = true
        avPlayer?.replaceCurrentItem(with: AVPlayerItem(url: streamURLInstance!))
        return PlayerStatus.PLAYING
    }
    
    func pause() -> PlayerStatus {
        print("invoking pause method on service")
        playerStatus = Constants.FLUTTER_RADIO_PAUSED
        if (isPlaying()) {
            avPlayer?.pause()
            pushEvent(eventName: Constants.FLUTTER_RADIO_PAUSED)
        }
        
        return PlayerStatus.PAUSE
    }
    
    func stop() -> PlayerStatus {
        print("invoking stop method on service")
        playerStatus = Constants.FLUTTER_RADIO_STOPPED
        if (isPlaying()) {
            pushEvent(eventName: Constants.FLUTTER_RADIO_STOPPED)
          
            avPlayer = nil
            avPlayerItem = nil
            commandCenter = nil
           
        }
        
        return PlayerStatus.STOPPED
    }
    
    func isPlaying() -> Bool {
        let status = (avPlayer?.rate != 0 && avPlayer?.error == nil) ? true : false
        print("isPlaying status: \(status)")
        return status
    }
    
    func setVolume(volume: NSNumber) -> Void {
        let formattedVolume = volume.floatValue;
        print("Setting volume to: \(formattedVolume)")
        avPlayer?.volume = formattedVolume
        pushEvent(typeEvent:"volume", eventName: formattedVolume)
    }

     func setTitle(title: String, subTitle:String) -> Void {
        print("Setting title to: \(title)")
        self.nowPlayingInfo[MPMediaItemPropertyTitle] = title
        self.nowPlayingInfo[MPMediaItemPropertyArtist] = subTitle
        self.updateNowPlayingInfo()
     }
    
    func setUrl(streamURL: String, playWhenReady: String) -> Void {
        self.streamUrl = streamURL
        let streamURLInstance = URL(string: streamURL)
        avPlayer?.replaceCurrentItem(with: AVPlayerItem(url: streamURLInstance!))
        
        if playWhenReady == "true" {
            self.playWhenReady = true
            _ = play()
        } else {
            self.playWhenReady = false
            _ = pause()
        }
    }
    
    private func pushEvent(typeEvent : String = "status", eventName: Any) {
        print("Pushing event: \(eventName)")
        NotificationCenter.default.post(name: Notifications.playbackNotification, object: nil, userInfo: [typeEvent: eventName])
    }
    
    private func initRemoteTransportControl(appName: String, subTitle: String, coverImageUrl: String) {
        
        commandCenter = MPRemoteCommandCenter.shared()
        
        // build now playing info
        self.loadCoverImageFromUrl(coverImageUrl: coverImageUrl)
        self.nowPlayingInfo[MPMediaItemPropertyTitle] = appName
        self.nowPlayingInfo[MPMediaItemPropertyArtist] = subTitle
        self.updateNowPlayingInfo()
        
        
        // basic command center options
        commandCenter?.togglePlayPauseCommand.isEnabled = true
        commandCenter?.playCommand.isEnabled = true
        commandCenter?.pauseCommand.isEnabled = true
        commandCenter?.nextTrackCommand.isEnabled = false
        commandCenter?.previousTrackCommand.isEnabled = false
        commandCenter?.changePlaybackRateCommand.isEnabled = false
        commandCenter?.skipForwardCommand.isEnabled = false
        commandCenter?.skipBackwardCommand.isEnabled = false
        commandCenter?.ratingCommand.isEnabled = false
        commandCenter?.likeCommand.isEnabled = false
        commandCenter?.dislikeCommand.isEnabled = false
        commandCenter?.bookmarkCommand.isEnabled = false
        commandCenter?.changeRepeatModeCommand.isEnabled = false
        commandCenter?.changeShuffleModeCommand.isEnabled = false
        
        // only available in iOS 9
        if #available(iOS 9.0, *) {
            commandCenter?.enableLanguageOptionCommand.isEnabled = false
            commandCenter?.disableLanguageOptionCommand.isEnabled = false
        }
        
        // control center play button callback
//            commandCenter?.playCommand.remo
        commandCenter?.playCommand.addTarget { (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus in
            print("command center play command...")
            _ = self.newPlay()
            return .success
        }
        
        // control center pause button callback
        commandCenter?.pauseCommand.addTarget { (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus in
            print("command center pause command...")
            _ = self.pause()
            return .success
        }
        
        // control center stop button callback
        commandCenter?.stopCommand.addTarget { (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus in
            print("command center stop command...")
            _ = self.stop()
            return .success
        }
        
        // create audio session for background playback and control center callbacks.
        let audioSession = AVAudioSession.sharedInstance()
        
        if #available(iOS 10.0, *) {
            
            try? audioSession.setCategory(.playback, mode: .default)
            try? audioSession.overrideOutputAudioPort(.speaker)
            try? audioSession.setActive(true)
        }
        
        UIApplication.shared.beginReceivingRemoteControlEvents()
        
    }

    private func initPlayerObservers() {
        print("Initializing player observers...")
        
        
        NotificationCenter.default.addObserver(self, selector: #selector(itemNewErrorLogEntry(_:)), name: .AVPlayerItemNewErrorLogEntry, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(itemFailedToPlayToEndTime(_:)), name: .AVPlayerItemFailedToPlayToEndTime, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(itemPlaybackStalled(_:)), name: .AVPlayerItemPlaybackStalled, object: nil)
        NotificationCenter.default.addObserver(self,
                    selector: #selector(systemVolumeDidChange),
                    name: NSNotification.Name(rawValue: "AVSystemController_SystemVolumeDidChangeNotification"),
                    object: nil
                )
        
        self.avPlayer?.addObserver(self, forKeyPath: #keyPath(AVPlayer.status), options: [.new,.initial], context: nil)
        self.avPlayer?.addObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem.status), options:[.new,.initial], context: nil)
        self.avPlayer?.addObserver(self, forKeyPath: #keyPath(AVPlayer.currentItem.isPlaybackBufferEmpty), options:[.new,.initial], context: nil)
    }
    
    
    
    @objc func itemNewErrorLogEntry(_ notification:Notification) {
        print(notification)
    }
    
    @objc func itemFailedToPlayToEndTime(_ notification:Notification){
        if let _ = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey]{
            _ = stop()
            print("Observer: Failed...\(String(describing: notification.userInfo))")
            playerStatus = Constants.FLUTTER_RADIO_ERROR

            pushEvent(eventName: Constants.FLUTTER_RADIO_ERROR)
        }
    }
    @objc func itemPlaybackStalled(_ notification:Notification){
        _ = stop()
        print("Observer: Stalled...")
        playerStatus = Constants.FLUTTER_RADIO_ERROR

        pushEvent(eventName: Constants.FLUTTER_RADIO_ERROR)
    }
    
    override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey: Any]?, context: UnsafeMutableRawPointer?) {
        
        if object is AVPlayer {

            switch keyPath {
            case #keyPath(AVPlayer.currentItem.isPlaybackBufferEmpty):
                let _: Bool
                if let newStatusNumber = change?[NSKeyValueChangeKey.newKey] as? Bool {
                    if newStatusNumber {
                        print("Observer: Stalling...")
                        playerStatus = Constants.FLUTTER_RADIO_LOADING

                        pushEvent(eventName: Constants.FLUTTER_RADIO_LOADING)
                    }
                }
            case #keyPath(AVPlayer.currentItem.status):
                let newStatus: AVPlayerItem.Status
                if let newStatusAsNumber = change?[NSKeyValueChangeKey.newKey] as? NSNumber {
                    newStatus = AVPlayerItem.Status(rawValue: newStatusAsNumber.intValue)!
                } else {
                    newStatus = .unknown
                }
                if newStatus == .readyToPlay {
                    print("Observer: Ready to play...")
                    pushEvent(eventName: isPlaying()
                                ? Constants.FLUTTER_RADIO_PLAYING
                                : Constants.FLUTTER_RADIO_PAUSED)
                    if !isPlaying() && self.playWhenReady {
                        _ = play()
                    }
                }
                
                if newStatus == .failed {
                    print("Observer: Failed...")
                    playerStatus = Constants.FLUTTER_RADIO_ERROR

                    pushEvent(eventName: Constants.FLUTTER_RADIO_ERROR)
                }
            case #keyPath(AVPlayer.status):
                var newStatus: AVPlayerItem.Status
                if let newStatusAsNumber = change?[NSKeyValueChangeKey.newKey] as? NSNumber {
                    newStatus = AVPlayerItem.Status(rawValue: newStatusAsNumber.intValue)!
                } else {
                    newStatus = .unknown
                }
    
                if newStatus == .failed {
                    print("Observer: Failed...")
                    
                    playerStatus = Constants.FLUTTER_RADIO_ERROR

                    pushEvent(eventName: Constants.FLUTTER_RADIO_ERROR)
                }
            case .none:
                print("none...")
            case .some(_):
                print("some...")
            }
//            print(keyPath)
        }
    }

    func loadCoverImageFromUrl(coverImageUrl: String) {
        DispatchQueue.global().async {
            if let url = URL(string: coverImageUrl)  {
                if let data = try? Data.init(contentsOf: url), let image = UIImage(data: data) {
                    let artwork = MPMediaItemArtwork(boundsSize: image.size, requestHandler: { (_ size : CGSize) -> UIImage in
                        return image
                    })
                    DispatchQueue.main.async {
                        print(self.nowPlayingInfo.description)
                        
                        self.nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
                        self.updateNowPlayingInfo()
                    }
                }
            }
        }
    }
    
    func updateNowPlayingInfo() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = self.nowPlayingInfo
    }
    
}




