cmake_minimum_required(VERSION 3.22.1)
project("doc_scanner_engine")

set(CMAKE_CXX_STANDARD 17)

# OpenCV is provided via the org.opencv:opencv AAR (Maven) using Prefab.
# With buildFeatures.prefab = true in build.gradle.kts, AGP exports the
# AAR's native headers+libs as a CMake config package named "opencv".
find_package(OpenCV REQUIRED CONFIG)

add_library(
        doc_scanner_engine
        SHARED
        edge_detector.cpp
        perspective_corrector.cpp
        curve_dewarper.cpp
        burst_fusion.cpp
        vulkan_compute_engine.cpp
        jni_bridge.cpp
)

find_library(vulkan-lib vulkan)

target_link_libraries(
        doc_scanner_engine
        log
        jnigraphics
        OpenCV::opencv_java4
        ${vulkan-lib}
)

target_compile_options(doc_scanner_engine PRIVATE -O3 -ffast-math -fopenmp)
target_link_options(doc_scanner_engine PRIVATE -fopenmp -static-openmp)
