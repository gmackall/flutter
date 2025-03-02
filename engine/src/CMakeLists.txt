cmake_minimum_required(VERSION 3.10)

# Set the project name and version
project(FlutterAndroidPlatform VERSION 1.0 LANGUAGES CXX)

# Specify the C++ standard
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# Base directory
#set(BASE_DIR ${CMAKE_SOURCE_DIR}/../../../..)
set(CMAKE_INCLUDE_PATH, ${CMAKE_SOURCE_DIR})

# Include directories
include_directories(
        ${CMAKE_SOURCE_DIR}/flutter/third_party/android_embedding_dependencies/include
        ${CMAKE_SOURCE_DIR}/flutter/third_party/android_embedding_dependencies/lib
        ${CMAKE_SOURCE_DIR}/flutter/shell/platform/android  # Add this line
	${CMAKE_SOURCE_DIR}/flutter  # Add this line
	${CMAKE_SOURCE_DIR}/
)

# Add source files
file(GLOB_RECURSE SOURCE_FILES
    ${CMAKE_SOURCE_DIR}/flutter/shell/platform/**/*.cc
    ${CMAKE_SOURCE_DIR}/flutter/shell/platform/**/*.h
)

# Add the executable
add_executable(FlutterAndroidPlatform ${SOURCE_FILES})

# Link libraries if necessary
# target_link_libraries(FlutterAndroidPlatform <library>)
