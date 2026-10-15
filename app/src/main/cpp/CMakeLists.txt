cmake_minimum_required(VERSION 3.13)
project(maru_engine)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    maru_upscaler 
    SHARED 
    maru_engine.cpp
    ../../core/src/engine.cpp
    ../../core/src/graph.cpp
)

find_library(vulkan-lib vulkan)

target_link_libraries(
    maru_upscaler 
    jnigraphics 
    android 
    log 
    ${vulkan-lib}
)

target_link_options(maru_upscaler PRIVATE -static-openmp)
