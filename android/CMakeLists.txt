cmake_minimum_required(VERSION 3.22)
project(ReactNativeVideoTexture)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(ReactAndroid REQUIRED CONFIG)

add_library(react-native-video-texture SHARED
  src/main/cpp/VideoTextureJNI.cpp
  src/main/cpp/AndroidFrameProvider.cpp
  ../cpp/FrameSourceHostObject.cpp
)

target_include_directories(react-native-video-texture PRIVATE ../cpp)

target_link_libraries(react-native-video-texture
  android
  log
  ReactAndroid::jsi
)
