require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'ReactNativeVideoTexture'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.homepage       = package['homepage']
  s.license        = package['license']
  s.author         = package['author']
  s.source         = { git: 'https://github.com/anorak-games/react-native-video-texture.git' }
  s.platform       = :ios, '16.4'
  s.swift_version  = '5.9'

  s.source_files   = 'ios/**/*.{h,m,mm,swift}', 'cpp/**/*.{h,cpp}'
  # C++-only headers must stay out of the ObjC module umbrella (jsi includes).
  s.private_header_files = 'cpp/**/*.h'
  s.frameworks     = 'AVFoundation', 'CoreVideo'

  # FrameSourceJSI.mm bridges frames to the WebGPU render worklet via the shared
  # cpp/FrameSourceHostObject (also compiled on Android).
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++20',
    'HEADER_SEARCH_PATHS' => '"$(PODS_TARGET_SRCROOT)/cpp"',
  }

  s.dependency 'ExpoModulesCore'
end
