cmake_minimum_required(VERSION 3.13)
project("maru_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ncnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
if(NOT EXISTS ${ncnn_DIR})
    message(FATAL_ERROR "ไม่พบ NCNN ที่ ${ncnn_DIR} — ดาวน์โหลด ncnn-<tag>-android.zip จาก github.com/Tencent/ncnn/releases แล้วแตกไฟล์ไว้ที่ app/src/main/cpp/ncnn/")
endif()
find_package(ncnn REQUIRED)

add_library(maru_upscaler SHARED maru_engine.cpp)
target_link_libraries(maru_upscaler ncnn jnigraphics android log)
target_link_options(maru_upscaler PRIVATE -static-openmp)
