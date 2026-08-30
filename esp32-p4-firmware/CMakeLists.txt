# Top-level CMakeLists for OmniChat-P4 firmware
cmake_minimum_required(VERSION 3.16)

set(EXTRA_COMPONENT_DIRS "${CMAKE_CURRENT_LIST_DIR}/components")

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(omnichat_p4)

# Several app files use the common strncpy(buf, src, sizeof(buf)-1) idiom which
# newer GCC flags as -Werror=stringop-truncation. Demote it to a warning.
idf_build_set_property(COMPILE_OPTIONS "-Wno-error=stringop-truncation" APPEND)
