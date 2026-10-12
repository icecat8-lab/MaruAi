cmake_minimum_required(VERSION 3.10.2)
project("maru_engine")
# โครงสร้างสำหรับเชื่อม NCNN จะถูกเขียนที่นี่
add_library(maru_engine SHARED maru_engine.cpp)
find_library(log-lib log)
target_link_libraries(maru_engine ${log-lib})
