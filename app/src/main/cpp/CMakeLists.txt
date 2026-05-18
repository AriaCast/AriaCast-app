cmake_minimum_required(VERSION 3.22.1)

project(ariacast_raop)

add_library(ariacast_raop SHARED
    alac_wrapper.cpp
)

find_library(log-lib log)

target_link_libraries(ariacast_raop ${log-lib})
