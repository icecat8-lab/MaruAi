cmake_minimum_required(VERSION 3.13)
project(maru_engine)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)


set(ncnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(maru_upscaler SHARED maru_engine.cpp)
target_link_libraries(maru_upscaler ncnn jnigraphics android log)
target_link_options(maru_upscaler PRIVATE -static-openmp)
