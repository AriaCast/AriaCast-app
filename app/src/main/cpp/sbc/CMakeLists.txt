cmake_minimum_required(VERSION 3.22.1)

# SBC decoder for the AriaCompanion BT board: BlueZ libsbc (LGPL-2.1-or-later, see README.md)
# plus a small JNI wrapper (sbc_jni.c).
project(ariacast_sbc C)

add_library(ariacast_sbc SHARED
    sbc_jni.c
    sbc.c
    sbc_primitives.c
    sbc_primitives_mmx.c
    sbc_primitives_sse.c
    sbc_primitives_neon.c
    sbc_primitives_armv6.c
    sbc_primitives_iwmmxt.c
)
target_include_directories(ariacast_sbc PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(ariacast_sbc PRIVATE HAVE_CONFIG_H)
target_compile_options(ariacast_sbc PRIVATE -O2 -Wno-unused-parameter)
