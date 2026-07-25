cmake_minimum_required(VERSION 3.22.1)

project("toolkit_security")

add_library(
        toolkit_security
        SHARED
        native-lib.cpp)

find_library(
        log-lib
        log)

target_link_libraries(
        toolkit_security
        ${log-lib})