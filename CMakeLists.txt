cmake_minimum_required(VERSION 3.21)

# Root project. Mirrors the Visual Studio solution that builds a single static library.
project(gzcom2 VERSION 0.1.0 LANGUAGES CXX)

# Option to build the library as a shared library (DLL) instead of static.
option(GZCOM_BUILD_SHARED "Build gzcom2 as a shared library (DLL)" OFF)
# Option to include the sample director implementation (main.cpp equivalent) into the library sources.
option(GZCOM_INCLUDE_SAMPLE_DIRECTOR "Include sample COM director implementation (main.cpp)" ON)

add_subdirectory(gzcom-dll)

# Packaging / export (optional). Users can include this project with add_subdirectory.
# For installation: cmake --install <build-dir> --config Release

