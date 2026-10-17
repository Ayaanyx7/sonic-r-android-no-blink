cmake_minimum_required(VERSION 3.22.1)
project(SonicRDobbyMod C)

set(DOBBY_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/dobby/include)
include_directories(${DOBBY_INCLUDE_DIR})

# Route compilation linkage to match whatever ABI architecture is active
set(CURRENT_ABI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/dobby/lib/${ANDROID_ABI})
set(DOBBY_LIB ${CURRENT_ABI_DIR}/libdobby.so)

add_library(mod SHARED mod.c)

find_library(log-lib log)
target_link_libraries(mod ${log-lib} ${DOBBY_LIB})
