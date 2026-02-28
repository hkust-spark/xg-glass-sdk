import 'package:flutter/widgets.dart';
import 'universal_frame_bridge.dart';

/// Entry-point used by the Android host app's FlutterEngine.
///
/// This module is intentionally "no UI": it exists only to expose MethodChannel/EventChannel APIs.
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  UniversalFrameBridge.instance.start();
  runApp(const SizedBox.shrink());
}
