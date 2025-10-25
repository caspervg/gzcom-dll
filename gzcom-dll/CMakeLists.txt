# Auto-discovered CMake build script for the gzcom2 library.
# Sources (.cpp) are automatically gathered from src/; headers are all files under include/.
# This replaces the previous hardcoded list to allow adding/removing files without editing CMake.
cmake_minimum_required(VERSION 3.21)

set(TARGET_NAME gzcom2)

# Public headers (recursive in case you add subfolders later).
file(GLOB_RECURSE GZCOM_PUBLIC_HEADERS CONFIGURE_DEPENDS
    ${CMAKE_CURRENT_SOURCE_DIR}/include/*.h
)

# All cpp sources directly under src (non-recursive to avoid pulling in build artifacts if subdirs appear later).
file(GLOB GZCOM_SOURCES CONFIGURE_DEPENDS
    ${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp
)

# Optionally exclude sample main.cpp from the library build.
if(NOT GZCOM_INCLUDE_SAMPLE_DIRECTOR)
    list(FILTER GZCOM_SOURCES EXCLUDE REGEX "/main\\.cpp$")
endif()

# Guard against empty source list (should not happen unless files are removed).
if(GZCOM_SOURCES STREQUAL "")
    message(FATAL_ERROR "No source files found in ${CMAKE_CURRENT_SOURCE_DIR}/src. At least one .cpp file is required.")
endif()

# Build static or shared based on option from parent CMakeLists.
if(GZCOM_BUILD_SHARED)
    add_library(${TARGET_NAME} SHARED ${GZCOM_SOURCES} ${GZCOM_PUBLIC_HEADERS})
    target_compile_definitions(${TARGET_NAME} PRIVATE GZCOM2_EXPORTS)
else()
    add_library(${TARGET_NAME} STATIC ${GZCOM_SOURCES} ${GZCOM_PUBLIC_HEADERS})
endif()

# Include directories for consumers.
target_include_directories(${TARGET_NAME}
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
)

# C++ standard settings.
set_target_properties(${TARGET_NAME} PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

if(MSVC)
    target_compile_definitions(${TARGET_NAME} PRIVATE _CRT_SECURE_NO_WARNINGS)
    target_compile_options(${TARGET_NAME} PRIVATE /W3
        $<$<CONFIG:Release>:/O2 /Oi /Gy>
        $<$<CONFIG:Debug>:/Od>
    )
    if(CMAKE_VERSION VERSION_GREATER_EQUAL 3.15)
        set_target_properties(${TARGET_NAME} PROPERTIES
            MSVC_RUNTIME_LIBRARY $<$<CONFIG:Debug>:MultiThreadedDebug>$<$<CONFIG:Release>:MultiThreaded>
        )
    endif()
endif()

# IDE source grouping.
source_group(TREE ${CMAKE_CURRENT_SOURCE_DIR}/include PREFIX "Header Files" FILES ${GZCOM_PUBLIC_HEADERS})
source_group(TREE ${CMAKE_CURRENT_SOURCE_DIR}/src PREFIX "Source Files" FILES ${GZCOM_SOURCES})

# Install rules.
install(TARGETS ${TARGET_NAME}
    EXPORT gzcom2Targets
    ARCHIVE DESTINATION lib
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)
install(DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/include/ DESTINATION include)

# Export targets for build-tree and install-tree usage.
export(EXPORT gzcom2Targets FILE ${CMAKE_CURRENT_BINARY_DIR}/gzcom2Targets.cmake NAMESPACE gzcom2::)
install(EXPORT gzcom2Targets FILE gzcom2Targets.cmake NAMESPACE gzcom2:: DESTINATION lib/cmake/gzcom2)

# Package config + version (parent configured these files already).
include(CMakePackageConfigHelpers)
write_basic_package_version_file(
    ${CMAKE_CURRENT_BINARY_DIR}/gzcom2ConfigVersion.cmake
    VERSION ${PROJECT_VERSION}
    COMPATIBILITY SameMajorVersion
)
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/../cmake/gzcom2Config.cmake.in
               ${CMAKE_CURRENT_BINARY_DIR}/gzcom2Config.cmake @ONLY)
install(FILES
    ${CMAKE_CURRENT_BINARY_DIR}/gzcom2Config.cmake
    ${CMAKE_CURRENT_BINARY_DIR}/gzcom2ConfigVersion.cmake
    DESTINATION lib/cmake/gzcom2
)

