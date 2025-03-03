cmake_minimum_required(VERSION 3.10)

# Set the project name and version
project(FlutterAndroidPlatform VERSION 1.0 LANGUAGES CXX)

# Specify the C++ standard
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# Base directory
set(BASE_DIR ${CMAKE_SOURCE_DIR}/../../../..)

# Include directories
include_directories(
		${BASE_DIR}/
		${BASE_DIR}/flutter/
		${BASE_DIR}/flutter/third_party/skia/
)

# Add source files
file(GLOB_RECURSE SOURCE_FILES
    ${BASE_DIR}/flutter/shell/platform/**/*.cc
    ${BASE_DIR}/flutter/shell/platform/**/*.h
)

# Add the executable
add_executable(FlutterAndroidPlatform ${SOURCE_FILES})

# Link libraries if necessary
# target_link_libraries(FlutterAndroidPlatform <library>)
