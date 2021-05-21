import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_radio_player/flutter_radio_player.dart';

void main() => runApp(
      MaterialApp(
        initialRoute: MyApp.ROUTE,
        routes: {
          MyApp.ROUTE: (_) => MyApp(),
        },
      ),
    );

class MyApp extends StatefulWidget {
  static const ROUTE = "/home";

  final playerState = FlutterRadioPlayer.flutter_radio_paused;

  final volume = 0.8;

  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  int _currentIndex = 0;

  final _flutterRadioPlayer = new FlutterRadioPlayer();

  @override
  void initState() {
    super.initState();
    initRadioService();
  }

  Future<void> initRadioService() async {
    try {
      await _flutterRadioPlayer.init(
        "Flutter Radio Example",
        "Live",
        "http://5.9.16.111:8210/arabic_live",
        "false",
        "https://drive.google.com/uc?export=view&id=${Platform.isAndroid ? "1rxEYh9xGG9PEFb5Fda3HKhXhoClG5GF7" : "1q5FweYYfwPDXyMiEBbQjA1rs6crOaGE3"}",
      );
    } on PlatformException {
      print("Exception occurred while trying to register the services.");
    }
  }

  @override
  Widget build(BuildContext context) {
<<<<<<< HEAD
    return Scaffold(
      appBar: AppBar(
        title: const Text('Flutter Radio Player Example'),
      ),
      body: Center(
        child: Column(
          children: <Widget>[
            StreamBuilder(
                stream: _flutterRadioPlayer.isPlayingStream!,
                initialData: widget.playerState,
                builder:
                    (BuildContext context, AsyncSnapshot<String?> snapshot) {
                  String returnData = snapshot.data ?? "";
                  print("object data: " + returnData);
                  switch (returnData) {
                    case FlutterRadioPlayer.flutter_radio_stopped:
                      return RaisedButton(
                          child: Text("Start listening now"),
                          onPressed: () async {
                            await initRadioService();
                          });
                      break;
                    case FlutterRadioPlayer.flutter_radio_loading:
                      return Text("Loading stream...");
                    case FlutterRadioPlayer.flutter_radio_error:
                      return RaisedButton(
                          child: Text("Retry ?"),
                          onPressed: () async {
                            await initRadioService();
                          });
                      break;
                    default:
                      return Row(
                          crossAxisAlignment: CrossAxisAlignment.center,
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: <Widget>[
                            IconButton(
                                onPressed: () async {
                                  print("button press data: " +
                                      snapshot.data.toString());
                                  await _flutterRadioPlayer.playOrPause();
                                },
                                icon: snapshot.data ==
                                        FlutterRadioPlayer.flutter_radio_playing
                                    ? Icon(Icons.pause)
                                    : Icon(Icons.play_arrow)),
                            IconButton(
                                onPressed: () async {
                                  await _flutterRadioPlayer.stop();
                                },
                                icon: Icon(Icons.stop))
                          ]);
                      break;
                  }
                }),
            StreamBuilder<double?>(
              stream: _flutterRadioPlayer.volumeStream!,
              builder: (_, snapshot) {
                final volume = snapshot.data ?? .5;

                return Column(
                  children: [
                    Slider(
                      value: volume,
                      min: 0.0,
                      max: 1.0,
                      divisions: 10,
                      onChanged: (value) =>
                          _flutterRadioPlayer.setVolume(value),
                    ),
                    Text(
                      "Volume: " + (volume * 100).toStringAsFixed(0),
                    ),
                  ],
                );
              },
            ),
            SizedBox(
              height: 15,
            ),
            Text("Metadata Track "),
            StreamBuilder<String?>(
                initialData: "",
                stream: _flutterRadioPlayer.metaDataStream,
                builder: (context, snapshot) {
                  return Text(snapshot.data ?? "**");
                }),
            RaisedButton(
                child: Text("Change URL"),
                onPressed: () async {
                  _flutterRadioPlayer.setUrl(
                    "http://5.9.16.111:8210/arabic_live",
                    "false",
                  );
                },
              ),
              SizedBox(
                height: 15,
              ),
              Text("Metadata Track "),
              StreamBuilder<String?>(
                  stream: _flutterRadioPlayer.metaDataStream,
                  builder: (context, snapshot) {
                    return Text(snapshot.data ?? "**");
                  }),
              RaisedButton(
                  child: Text("Change URL"),
                  onPressed: () async {
                    _flutterRadioPlayer.setUrl(
                      "http://5.9.16.111:8210/arabic_live",
                      "false",
                    );
                  })
            ],
          ),
        ),
      ),
      bottomNavigationBar: new BottomNavigationBar(
          currentIndex: this._currentIndex,
          onTap: (int index) {
            setState(() {
              _currentIndex = index;
            });
          },
          items: [
            BottomNavigationBarItem(
                icon: new Icon(Icons.home), title: new Text('Home')),
            BottomNavigationBarItem(
                icon: new Icon(Icons.pages), title: new Text('Second Page'))
          ]),
    );
  }
}
